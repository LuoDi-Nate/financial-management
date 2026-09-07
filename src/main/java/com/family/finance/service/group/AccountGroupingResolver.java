package com.family.finance.service.group;

import com.family.finance.domain.account.Account;
import com.family.finance.repository.AccountGroupMapper;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodAccountGroupMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.20 · <b>账户维值解析器 —— 所有按账户聚合的路径都必须过它。</b>
 *
 * <h3>它解决什么</h3>
 *
 * <p>今天「账户」在聚合里是个<b>特例</b>:{@code groupBy(result, "acct".equals(dim) ? null : dim)}
 * —— 传 {@code null} 表示按账户,而其余维度({@code assetClass / owner / platform / currency / type})
 * 都走一张 {@code accountId → 标签} 的映射表。</p>
 *
 * <p>这一版把账户<b>也变成一个有标签的普通维度</b>,标签由这里给:</p>
 *
 * <pre>
 *   已分组 → 组名(该期<b>定格</b>的组名)
 *   未分组 → 账户名
 * </pre>
 *
 * <p>带来三个结构性好处,都不需要额外保证:</p>
 * <ol>
 *   <li><b>零组态逐字一致是必然的</b> —— 没建组时每个账户的标签就是自己的账户名,
 *       输出与 v1.19.16 完全相同。所以这一版<b>不需要开关</b>。</li>
 *   <li><b>漏不掉</b> —— 新增一条聚合路径时,它必须来问标签,没有第二个地方可问。</li>
 *   <li><b>下钻只是换一次标签</b> —— {@code expandGroupId} 让该组成员回落成账户名,其余不变。</li>
 * </ol>
 *
 * <h3>它<b>不</b>负责什么</h3>
 *
 * <p><b>不负责求和。</b>求和仍在各自的引擎里(AttributionEngine / PivotEngine / …)。
 * 如果解析器也管求和,就又多了一条口径 —— 而本项目已经有 pivot 与 period_summary
 * 两条无共用求和逻辑的路径差 0.01 的前科。</p>
 *
 * <h3>组内流转为什么不用在这里处理</h3>
 *
 * <p>因为它是<b>「按组求净额」的副产品</b>,不需要任何对手方识别:</p>
 * <pre>
 *   A → B(两端都在组内):A.transferOut += X · B.transferIn += X
 *                        组内 Σ(in − out) = X − X = 0        ← 自动抵消
 *   A → C(C 在组外)   :A.transferOut += X · C 不参与求和
 *                        组内 Σ(in − out) = −X               ← 正确计为流出
 * </pre>
 * <p>代价是这条性质<b>很容易被无意破坏</b>:有人把某处改成毛额求和,抵消就失效了,
 * 而结果<b>仍然自洽</b>(毛额也闭合)、不报错。所以 {@code v1200-NET-NOT-GROSS} 护栏钉着它。</p>
 */
@Service
@RequiredArgsConstructor
public class AccountGroupingResolver {

    private final AccountMapper accountMapper;
    private final AccountGroupMapper groupMapper;
    private final PeriodAccountGroupMapper frozenMapper;

    /** 账户维值:一个账户对应一个显示用的维值,以及它是不是一个组 */
    public record Label(String value, boolean grouped, Long groupId) {}

    /**
     * 某一期的账户维值映射。
     *
     * @param periodId      看哪一期。<b>已关账的期读定格行</b>(FR-468);
     *                      传 {@code null} 或该期没有定格行时,回落到当前成员关系
     * @param expandGroupId 下钻:这个组的成员回落成账户名(FR-465);{@code null} = 全部折叠
     */
    public Map<Long, Label> labelsFor(long familyId, Long periodId, Long expandGroupId) {
        Map<Long, String> accountName = new HashMap<>();
        for (Account a : accountMapper.findAllByFamily(familyId)) {
            accountName.put(a.getId(), a.getDisplayName());
        }

        Map<Long, Label> out = new LinkedHashMap<>();
        // 先全部填成「账户名」—— 零组态时这就是最终结果,与 v1.19.16 逐字一致
        for (var e : accountName.entrySet()) {
            out.put(e.getKey(), new Label(e.getValue(), false, null));
        }

        for (var g : groupingOf(familyId, periodId)) {
            if (expandGroupId != null && expandGroupId.equals(g.groupId())) continue;   // 这个组被点开了
            if (!out.containsKey(g.accountId())) continue;                              // 账户已删/不在本家
            out.put(g.accountId(), new Label(g.groupName(), true, g.groupId()));
        }
        return out;
    }

    /** 只要维值字符串的场景(归因标签表用) */
    public Map<Long, String> valuesFor(long familyId, Long periodId, Long expandGroupId) {
        Map<Long, String> out = new LinkedHashMap<>();
        labelsFor(familyId, periodId, expandGroupId).forEach((k, v) -> out.put(k, v.value()));
        return out;
    }

    /**
     * 该期的分组关系。
     *
     * <p><b>已关账的期必须读定格行</b> —— 读当前成员关系的话,今天挪一个账户出组,
     * 12 期趋势图会全变(每个数字自身都对,但用户会当成算错了)。
     * 定格行不存在(未关账 / v1.20 之前关的账)时回落到当前关系 —— 这与
     * {@code FactViewServiceImpl} 处理 {@code period_account_attr} 缺失时的做法一致。</p>
     */
    private List<PeriodAccountGroupMapper.Row> groupingOf(long familyId, Long periodId) {
        if (periodId != null) {
            List<PeriodAccountGroupMapper.Row> frozen = frozenMapper.findByPeriod(periodId);
            if (!frozen.isEmpty()) return frozen;
        }
        Map<Long, String> groupName = new HashMap<>();
        groupMapper.findByFamily(familyId).forEach(g -> groupName.put(g.getId(), g.getName()));
        return groupMapper.findMembersByFamily(familyId).stream()
                .map(m -> new PeriodAccountGroupMapper.Row(
                        m.accountId(), m.groupId(), groupName.getOrDefault(m.groupId(), "分组")))
                .toList();
    }

    /** 这个家有没有建过组 —— 用来在页面上决定要不要显示「下钻」这类只有分组才有意义的控件 */
    public boolean hasAnyGroup(long familyId) {
        return !groupMapper.findByFamily(familyId).isEmpty();
    }
}
