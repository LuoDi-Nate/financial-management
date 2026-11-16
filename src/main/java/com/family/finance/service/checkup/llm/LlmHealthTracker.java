package com.family.finance.service.checkup.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个 LLM 平台<b>最后一次调用的结果</b>,给管理页当健康读数。
 *
 * <h3>为什么要有这个东西</h3>
 *
 * <p>2026-09-22 复盘出来的:主选 DeepSeek 从 <b>09-02</b> 起就欠费失败,备选阿里云百炼从
 * <b>09-20</b> 起也挂了 —— 中间整整 20 天,AI 体检、调仓建议、超级 Agent、AI 月报全部出不来,
 * <b>而没有任何一个人知道</b>。</p>
 *
 * <p>为什么没人知道:每一层都「优雅降级」了。页面上显示的是
 * 「AI · 暂不可用」「暂未能生成建议」—— 那是<b>正确</b>的用户体验,但它同时也意味着
 * <b>故障和「这个月没人点过 AI」长得一模一样</b>。降级把异常伪装成了常态。</p>
 *
 * <p>最后是<b>账单</b>把这件事捅出来的 —— 那是最差的发现方式:等你看到账单,
 * 钱已经花完了,而且你还会以为是被人刷了。</p>
 *
 * <h3>为什么只存在内存里</h3>
 *
 * <p>它回答的是「<b>现在</b>还好吗」,不是「历史上出过几次错」——
 * 后者日志里有。进程重启后读数为空,页面如实显示「还没调用过」,
 * 不假装健康(这一点很要紧:把「不知道」显示成「正常」正是这次事故的形状)。</p>
 */
@Slf4j
@Component
public class LlmHealthTracker {

    /**
     * @param ok        这次成功没有
     * @param at        什么时候
     * @param accountFatal 是不是账户级故障(凭据 / 欠费 / 权限)—— 这类不会自己好
     * @param brief     上游原话的摘要(失败时)
     */
    public record Outcome(boolean ok, Instant at, boolean accountFatal, String brief) {}

    private final Map<String, Outcome> last = new ConcurrentHashMap<>();

    public void forget(String platform) { last.remove(platform); }

    public void recordOk(String platform) {
        last.put(platform, new Outcome(true, Instant.now(), false, null));
    }

    public void recordFail(String platform, boolean accountFatal, String brief) {
        last.put(platform, new Outcome(false, Instant.now(), accountFatal,
                brief == null ? "" : brief.length() > 160 ? brief.substring(0, 160) : brief));
    }

    /** 平台 → 最后一次结果(按记录先后)*/
    public Map<String, Outcome> snapshot() {
        return new LinkedHashMap<>(last);
    }

    /**
     * 「AI 整体是不是废了」。
     *
     * <p>判据刻意收紧成<b>账户级故障</b>:偶发超时、单型号额度用尽都会自己恢复,
     * 拿它们报警等于制造噪音,而噪音久了就没人看了 —— 那会把这条读数变成
     * 又一个「一直红着所以被忽略」的东西。</p>
     *
     * <p>而账户级故障(401 / 402 / 403:凭据、欠费、权限)<b>不会自己好</b>,
     * 必须有人去充值或换凭据。这正是需要有人知道的那一类。</p>
     *
     * @return true = 有过调用,且<b>所有</b>调过的平台最后一次都是账户级故障
     */
    public boolean allAccountsDown() {
        Map<String, Outcome> m = snapshot();
        if (m.isEmpty()) return false;              // 没调用过 ≠ 坏了
        return m.values().stream().allMatch(o -> !o.ok() && o.accountFatal());
    }

    /** 距离最后一次成功调用过了多久(从没成功过 → null)*/
    public Duration sinceLastOk() {
        Instant best = null;
        for (Outcome o : snapshot().values()) {
            if (o.ok() && (best == null || o.at().isAfter(best))) best = o.at();
        }
        return best == null ? null : Duration.between(best, Instant.now());
    }
}
