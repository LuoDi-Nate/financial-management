package com.family.finance.service.entry;

import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.service.MoneyFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * v1.20 FR-450~453 · <b>别让一次改动悄悄推翻上一次的确认。</b>
 *
 * <h3>它在解决什么</h3>
 *
 * <p>线上用户(issue #17)原话:</p>
 * <blockquote>
 *   「一旦后期二次修改收入……就会导致<b>已经校准的余额失效</b>!
 *    我觉得至少有个<b>强烈的提醒</b>……因为校验后的余额……
 *    它的<b>稳定性比收入的录入要高</b>。」
 * </blockquote>
 *
 * <p>他说得对,而且<b>实际情况比他描述的更重</b> —— 在 beta 上照他的路径用真浏览器复现过:
 * 删一笔收入再补录一笔金额不同的,<b>已关账期的期末余额合计跟着同步变了</b>
 * (差额与那两笔的净额分毫不差),而<b>页面零提示</b>。
 * 那笔余额不是「失效」,是<b>被直接改掉了</b>。</p>
 *
 * <h3>为什么提示放在改动【之后】</h3>
 *
 * <p>他的痛点是<b>「不知情」,不是「不该改」</b>。而且他改收入,往往正是因为收入记错了 ——
 * 余额跟着修正<b>本来就是对的</b>。事后如实告知已经解决了问题,而且:</p>
 * <ul>
 *   <li><b>零额外点击</b> —— 严格符合 FR-450 边界「提示是信息不是拦截」</li>
 *   <li>数字是<b>实际值</b>,不是改动前的预测值</li>
 * </ul>
 *
 * <p>代价是真实的:如果他确实不想让余额变,得再动一次去修。这是明知的取舍(TDD §二 选型四)。</p>
 *
 * <h3>另一半:改了却看不到效果</h3>
 *
 * <p>复现时还坐实了一个他没提、但一定会让人困惑的条件:
 * <b>当月还没填余额的账户,它上面的变动不会进「本期净资产变化」</b> ——
 * 那个数字对比的是「上月末 → 本月末」,而这个账户的本月末是空的。
 * 「改了数据但数字不动」因此确实会发生,而页面从来没解释过这个条件(FR-453)。</p>
 */
@Service
@RequiredArgsConstructor
public class BalanceGuardService {

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

    private final SnapshotMapper snapshotMapper;
    private final AccountMapper accountMapper;

    /** 改动前后的余额快照,用来算「动了什么」 */
    public record Before(Long accountId, BigDecimal balance, String calibratedAt) {}

    /**
     * 改动前先记一笔。{@code accountId} 为 null(拿不到账户)时返回 null,调用方直接跳过提示 ——
     * <b>宁可不提示,也不要提示错的账户</b>。
     */
    public Before snapshot(Long accountId, long periodId) {
        if (accountId == null) return null;
        PeriodSnapshot s = snapshotMapper.findByPeriodAndAccount(periodId, accountId).orElse(null);
        return new Before(accountId, s == null ? null : s.getEndBalance(),
                s == null || s.getSubmittedAt() == null ? null : s.getSubmittedAt().format(MD));
    }

    /**
     * 改动之后给一句话。返回 null = 没什么值得说的(余额没变、且该账户本期已校准)。
     *
     * <p>触发条件<b>窄到可枚举</b>,这是刻意的:提示一多就会被当噪声划过去,那这一版就白做了。</p>
     * <ul>
     *   <li>该账户本期<b>已校准</b> 且 本次改动<b>确实改了余额</b> → 说清校准时间与前后值(FR-450)</li>
     *   <li>该账户本期<b>没填余额</b> → 说清这笔改动不进本期净资产变化(FR-453)</li>
     *   <li>其余情况 → 不吭声</li>
     * </ul>
     */
    public String afterNote(Before before, long periodId) {
        if (before == null || before.accountId() == null) return null;
        String name = accountMapper.findById(before.accountId())
                .map(a -> a.getDisplayName()).orElse("这个账户");

        PeriodSnapshot now = snapshotMapper.findByPeriodAndAccount(periodId, before.accountId()).orElse(null);
        BigDecimal after = now == null ? null : now.getEndBalance();

        // FR-453 · 本月还没填余额 —— 改了也看不到效果,这条比「余额被改了」更让人困惑
        if (before.calibratedAt() == null && after == null) {
            return "顺带一说:「" + name + "」本月还没填余额,所以这笔改动"
                 + "不会反映到「本期净资产变化」上 —— 那个数字对比的是上月末到本月末,"
                 + "而这个账户的本月末还是空的。";
        }

        // FR-450 · 已校准过,而且这次真的把它改了
        if (before.calibratedAt() != null && changed(before.balance(), after)) {
            return "注意:「" + name + "」的余额在 " + before.calibratedAt() + " 校准过,"
                 + "这次改动把它从 " + money(before.balance()) + " 变成了 " + money(after) + "。"
                 + "如果这不是你想要的,去填报页把这个账户的余额重新校准一下。";
        }
        return null;
    }

    private static boolean changed(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return false;
        if (a == null || b == null) return true;
        return a.compareTo(b) != 0;
    }

    private static String money(BigDecimal v) {
        return v == null ? "(空)" : MoneyFormat.format2("CNY", v);
    }
}
