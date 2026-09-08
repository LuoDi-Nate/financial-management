package com.family.finance.service.group;

import com.family.finance.domain.account.AccountType;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.Sparkline;
import com.family.finance.factview.TrendPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * v1.20 FR-482/483 · 把账户级指标行<b>按组折叠</b>。
 *
 * <h3>它服务三个组件,而它们共用同一份数据</h3>
 *
 * <p>盘查两个核心页面的 22 个组件后,需要跟着分组走的只有 4 个,其中三个
 * ——「按账户分布」图、dashboard 账户列表、reports 账户级收益表 ——
 * <b>吃的是同一份 {@code accountRows}</b>。所以改这一处,三个组件同时对。
 * 那张横条图连一行 JS 都不用动。</p>
 *
 * <h3>零组态必须逐字一致</h3>
 *
 * <p>没建组时 {@link AccountGroupingResolver} 给每个账户的标签就是它自己的账户名,
 * 于是这里<b>一个组都分不出来</b>,输出 = 逐行 {@code AccountRowView.of(p)} = 原样。
 * 这不是靠测试保证的,是<b>结构上必然</b>的。</p>
 *
 * <h3>顺序:组行插在它「最大的那个成员」原来的位置</h3>
 *
 * <p>不是排到最前也不是排到最后。列表本来按当前价值降序,把组行放在最大成员的位次上,
 * 用户建组前后<b>视线落点不变</b> —— 他记得「招行在第三行」,建组后「日常周转」还在第三行。</p>
 */
@Service
@RequiredArgsConstructor
public class AccountRowGrouper {

    private final AccountGroupingResolver resolver;

    /**
     * @param periodId 看哪一期(已关账期读定格分组);null = 用当前成员关系
     * @param expandGroupId 展开这个组 —— 它的成员回落成普通账户行
     */
    public List<AccountRowView> fold(long familyId, Long periodId, Long expandGroupId,
                                     List<AccountPerformance> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<Long, AccountGroupingResolver.Label> labels = resolver.labelsFor(familyId, periodId, expandGroupId);

        // 分组桶(按首次出现顺序),以及未分组的原样行 —— 用一个 list 保住原始次序
        List<Object> slots = new ArrayList<>();                   // AccountPerformance 或 Long(组 id 占位)
        Map<Long, List<AccountPerformance>> buckets = new LinkedHashMap<>();
        Map<Long, String> groupNames = new LinkedHashMap<>();

        for (AccountPerformance p : rows) {
            AccountGroupingResolver.Label lb = p.accountId() == null ? null : labels.get(p.accountId());
            if (lb == null || !lb.grouped()) { slots.add(p); continue; }
            Long gid = lb.groupId();
            if (!buckets.containsKey(gid)) {
                buckets.put(gid, new ArrayList<>());
                groupNames.put(gid, lb.value());
                slots.add(gid);                                    // 占位:组行落在【首个成员】的位置
            }
            buckets.get(gid).add(p);
        }

        List<AccountRowView> out = new ArrayList<>();
        for (Object slot : slots) {
            if (slot instanceof AccountPerformance p) { out.add(AccountRowView.of(p)); continue; }
            Long gid = (Long) slot;
            List<AccountPerformance> members = buckets.get(gid);
            // 只有一个成员的组不值得折叠成组行 —— 折了反而多一次点击才能看到指标,
            // 而且比率类会平白变成「—」。一个人的队伍不叫队伍。
            if (members.size() == 1) { out.add(AccountRowView.of(members.get(0))); continue; }
            out.add(merge(gid, groupNames.get(gid), members));
        }
        return out;
    }

    /**
     * 扁平化:组行之后<b>紧跟它的成员行</b>(默认隐藏,前端点「展开」才显示)。
     *
     * <p>为什么不让模板嵌套两层 {@code th:each}:账户表有 11 个可选指标列、
     * 每格都带 {@code data-mcol} 门控与正负着色,<b>抄第二份 = 以后改一处忘另一处</b>。
     * 扁平之后模板仍是<b>一个</b>循环,只有「账户名」「操作」两格需要分支。</p>
     *
     * <p>调用方注意:横条图与「N 个账户」计数<b>要用折叠后的顶层列表,不能用这个</b> ——
     * 成员行会把钱算两遍。</p>
     */
    public List<AccountRowView> flatten(List<AccountRowView> folded) {
        List<AccountRowView> out = new ArrayList<>();
        for (AccountRowView v : folded) {
            out.add(v);
            if (!v.grouped()) continue;
            for (AccountPerformance m : v.members()) out.add(AccountRowView.memberOf(m, v.groupId()));
        }
        return out;
    }

    /** 合并一个组:能加的加、能派生的派生、<b>不能算的一律 null</b>(理由见 {@link AccountRowView}) */
    private AccountRowView merge(Long groupId, String groupName, List<AccountPerformance> ms) {
        BigDecimal currentValue = sum(ms, AccountPerformance::currentValue);
        BigDecimal cumPnl       = sum(ms, AccountPerformance::cumPnl);
        BigDecimal netPrincipal = sum(ms, AccountPerformance::netPrincipal);
        BigDecimal latestPnl    = sum(ms, AccountPerformance::latestPnl);
        BigDecimal momAmount    = sum(ms, AccountPerformance::momAmount);
        BigDecimal sharePct     = sum(ms, AccountPerformance::sharePct);

        // 本期Δ% 可以派生:上期余额 = 本期余额 − Δ。分母为 0 时给 null,不给 ∞ 也不给 0。
        BigDecimal momPct = null;
        if (currentValue != null && momAmount != null) {
            BigDecimal prev = currentValue.subtract(momAmount);
            if (prev.signum() != 0) {
                momPct = momAmount.multiply(BigDecimal.valueOf(100))
                        .divide(prev.abs(), 2, RoundingMode.HALF_EVEN);
            }
        }

        // 成员同型才给类型,混合给 null —— 图上的配色宁可退到中性,也别拿第一个成员的类型冒充全组
        AccountType type = single(ms.stream().map(AccountPerformance::accountType).toList());
        String ccy       = single(ms.stream().map(AccountPerformance::accountCurrency).toList());

        List<TrendPoint> spark = mergeSparklines(ms);
        Integer months = ms.stream().map(AccountPerformance::monthsHeld)
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);

        List<AccountPerformance> sorted = ms.stream()
                .sorted(Comparator.comparing(
                        (AccountPerformance p) -> p.currentValue() == null ? BigDecimal.ZERO : p.currentValue().abs())
                        .reversed())
                .toList();

        return new AccountRowView(
                null, groupName, type, ccy,
                currentValue,
                null,                       // xirr —— 资金加权年化,不可加也不可平均
                cumPnl, netPrincipal, latestPnl, momAmount, momPct, sharePct,
                null,                       // maxDrawdown —— 合并时序上开/关户会造出假回撤
                months,
                Sparkline.points(spark), Sparkline.trend(spark),
                null,                       // returnBase —— 同 xirr
                null,                       // expectedReturnPct —— 预期是账户级配置,组没有
                null,                       // planActualDiffPct —— 没有预期就没有预实
                true, groupId, sorted, null);
    }

    /**
     * 成员的月末余额序列<b>按账期对齐相加</b>。
     *
     * <p>成员的期数常常不同(有的账户是后来才开的)。按 periodId 对齐、缺失当 0 相加,
     * 得到的正是「这个组在每个月末一共有多少钱」—— 前几期只有老账户,后面才叠上新账户。
     * 这是对的:组在那几个月确实只有那么多钱。</p>
     *
     * <p>但正因如此,曲线上会有一个新成员加入造成的<b>跳升</b>,它<b>不是收益</b>。
     * 所以这条合并曲线只用来<b>画形状</b>({@code sparklinePoints}),
     * <b>不拿去算回撤</b> —— 回撤对跳变敏感,会得到一个假数字。</p>
     */
    private static List<TrendPoint> mergeSparklines(List<AccountPerformance> ms) {
        Map<Long, TrendPoint> byPeriod = new TreeMap<>();
        for (AccountPerformance p : ms) {
            if (p.sparkline() == null) continue;
            for (TrendPoint tp : p.sparkline()) {
                if (tp == null || tp.periodId() == null || tp.value() == null) continue;
                TrendPoint cur = byPeriod.get(tp.periodId());
                byPeriod.put(tp.periodId(), cur == null ? tp
                        : new TrendPoint(tp.periodId(), tp.periodStart(), tp.label(),
                                         cur.value().add(tp.value()), cur.live() || tp.live()));
            }
        }
        return byPeriod.values().stream()
                .sorted(Comparator.comparing(TrendPoint::periodStart,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static BigDecimal sum(List<AccountPerformance> ms,
                                  java.util.function.Function<AccountPerformance, BigDecimal> f) {
        BigDecimal acc = null;
        for (AccountPerformance p : ms) {
            BigDecimal v = f.apply(p);
            if (v == null) continue;
            acc = acc == null ? v : acc.add(v);
        }
        return acc;
    }

    /** 全员相同才返回那个值,否则 null(混合) */
    private static <T> T single(List<T> vs) {
        T first = null;
        for (T v : vs) {
            if (v == null) return null;
            if (first == null) first = v;
            else if (!first.equals(v)) return null;
        }
        return first;
    }
}
