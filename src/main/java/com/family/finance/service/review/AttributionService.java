package com.family.finance.service.review;

import com.family.finance.calc.review.AttributionEngine;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.lens.AssetClass;
import com.family.finance.domain.member.Member;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactSlice;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.member.MemberDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * v1.2 · 归因装配(FactSlice → AttributionEngine 输入 + 账户级标签 + 12 期趋势)· tech-design v1.2 §1。
 * 维度集 = 账户 / 资产类型 / 成员 / 平台 / 币种 / 账户类型(行业不做:持仓账户账户级行业恒空,见 PRD 修订)。
 */
@Service
@RequiredArgsConstructor
public class AttributionService {

    public static final Map<String, String> DIMS = new LinkedHashMap<>() {{
        put("acct", "账户"); put("assetClass", "资产类型"); put("owner", "成员");
        put("platform", "平台"); put("currency", "币种"); put("type", "账户类型");
    }};

    private final AccountMapper accountMapper;
    /** v1.20 · 账户维值的唯一来源。所有按账户聚合的路径都必须过它 */
    private final com.family.finance.service.group.AccountGroupingResolver groupingResolver;
    /** v1.15 FR-382 · 名字映射走名录(含已归档)—— 归档一个人,不该让历史数据里的他变成无名氏 */
    private final MemberDirectory memberDirectory;

    /** anchor 期归因(rows = slice 中 anchor 期各账户事实;delta/human/opening 由调用方按现有 KPI 口径给) */
    public AttributionEngine.Result attribute(long familyId, List<AccountPeriodFact> anchorRows,
                                              BigDecimal delta, BigDecimal humanEarned, BigDecimal opening) {
        return attribute(familyId, anchorRows, delta, humanEarned, opening, null, null);
    }

    /** v1.20 · 带该期与下钻的重载。{@code periodId} 决定读哪一期的分组定格 */
    public AttributionEngine.Result attribute(long familyId, List<AccountPeriodFact> anchorRows,
                                              BigDecimal delta, BigDecimal humanEarned, BigDecimal opening,
                                              Long periodId, Long expandGroupId) {
        Map<Long, Map<String, String>> labels = accountLabels(familyId, periodId, expandGroupId);
        List<AttributionEngine.AcctInput> inputs = new ArrayList<>();
        for (AccountPeriodFact f : anchorRows) {
            if (f.accountType() == AccountType.LOAN) continue;   // 负债不进钱赚(与 lens 口径一致)
            inputs.add(new AttributionEngine.AcctInput(
                    f.accountId(), f.accountName(), f.accountCurrency(),
                    f.periodPnlBase(), f.periodPnlOrig(),
                    f.endBalanceBase(), f.endBalanceOrig(),
                    f.previousEndBalanceBase(), f.previousEndBalanceOrig(),
                    labels.getOrDefault(f.accountId(), Map.of())));
        }
        return AttributionEngine.attribute(inputs, delta, humanEarned, opening);
    }

    /** 近 N 期「钱赚」按维度分组(时间升序 · 含进行中的当月;组值 = Σ periodPnlBase;LOAN 剔除) */
    public List<AttributionEngine.TrendRow> trend(long familyId, FactSlice slice, String dimKey, int lastN) {
        return trend(familyId, slice, dimKey, lastN, null);
    }

    /**
     * v1.20 · 趋势的每一期<b>各读各的分组定格</b>。
     *
     * <p>不能一次算好标签给 12 期共用 —— 那等于「按当前分组重画历史」,
     * 今天把一个账户挪出组,整张图会变。每期一次查询(12 次),量级完全可接受。</p>
     */
    public List<AttributionEngine.TrendRow> trend(long familyId, FactSlice slice, String dimKey, int lastN,
                                                  Long expandGroupId) {
        Map<Long, Map<String, String>> labels = accountLabels(familyId, null, expandGroupId);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yy-MM");
        Map<Long, List<AccountPeriodFact>> byPeriod = slice.byPeriod();
        // v1.18.3 · 趋势含当月(与排行榜同锚)。v1.18.1 曾只取已关账期,因为进行中的期
        //   会出现「转账已登记、余额没涨」的假亏损把纵轴带偏 —— 但那个病根已经在
        //   v1.18.1 后半段修掉(钱现在会落进现金行,不再被估值抹掉)。
        //   趋势图和上面的排行榜必须同锚,否则最后一根柱子和排行榜说的不是一件事。
        List<Long> pids = slice.periodIds();
        List<AttributionEngine.TrendRow> out = new ArrayList<>();
        for (Long pid : pids.subList(Math.max(0, pids.size() - lastN), pids.size())) {
            Map<String, BigDecimal> group = new LinkedHashMap<>();
            String label = "";
            // v1.20 · 账户维值按【这一期】的定格分组取,其余维度共用一份标签
            Map<Long, String> acctThisPeriod = "acct".equals(dimKey)
                    ? groupingResolver.valuesFor(familyId, pid, expandGroupId) : Map.of();
            for (AccountPeriodFact f : byPeriod.getOrDefault(pid, List.of())) {
                if (f.accountType() == AccountType.LOAN) continue;
                if (f.periodStart() != null) label = f.periodStart().format(fmt);
                BigDecimal pnl = f.periodPnlBase() == null ? BigDecimal.ZERO : f.periodPnlBase();
                if (pnl.signum() == 0) continue;
                String key = "acct".equals(dimKey)
                        ? acctThisPeriod.getOrDefault(f.accountId(), f.accountName())
                        : labels.getOrDefault(f.accountId(), Map.of()).get(dimKey);
                group.merge(key == null ? "未分类" : key, pnl, BigDecimal::add);
            }
            out.add(new AttributionEngine.TrendRow(label, group));
        }
        return out;
    }

    /** 账户级维度标签(与 lens 同源口径) */
    /**
     * v1.20 · 账户维值也进这张表 —— 见 {@link com.family.finance.service.group.AccountGroupingResolver}。
     *
     * <p>以前 {@code acct} 是个特例({@code groupBy} 传 null 走 {@code accountName}),
     * 其余维度才走标签。现在它和别人一样了:<b>已分组 → 组名,未分组 → 账户名</b>。
     * 零组态时标签就是账户名,所以输出与 v1.19.16 逐字一致 —— 这是结构上必然的。</p>
     */
    private Map<Long, Map<String, String>> accountLabels(long familyId, Long periodId, Long expandGroupId) {
        Map<Long, String> memberName = memberDirectory.listAll(familyId).stream()
                .collect(Collectors.toMap(Member::getId, Member::getDisplayName));
        Map<Long, String> acctValue = groupingResolver.valuesFor(familyId, periodId, expandGroupId);
        Map<Long, Map<String, String>> out = new HashMap<>();
        for (Account a : accountMapper.findActiveByFamily(familyId)) {
            Map<String, String> m = new HashMap<>();
            m.put("acct", acctValue.getOrDefault(a.getId(), a.getDisplayName()));
            AssetClass cls = AssetClass.fromName(a.getAssetClass());
            if (cls == null) cls = AssetClass.defaultFor(a.getType(), a.getProductCategoryCode());
            m.put("assetClass", cls == null ? null : cls.getLabel());
            m.put("owner", a.getPrimaryOwnerMemberId() == null ? "共同"
                    : memberName.getOrDefault(a.getPrimaryOwnerMemberId(), "成员#" + a.getPrimaryOwnerMemberId()));
            m.put("platform", a.getPlatformTag() == null || a.getPlatformTag().isBlank() ? null : a.getPlatformTag());
            m.put("currency", a.getCurrency());
            m.put("type", a.getType().getLabel());
            out.put(a.getId(), m);
        }
        return out;
    }
}
