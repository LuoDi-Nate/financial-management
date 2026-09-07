package com.family.finance.service.group;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.group.AccountGroup;
import com.family.finance.repository.AccountGroupMapper;
import com.family.finance.repository.AccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.20 · 账户组的增删改 + 推荐 + 代价提示。
 *
 * <p><b>推荐 ≠ 自动创建</b>(FR-469):系统只按现有属性给建议并预勾成员,
 * 用户不点就一个组都不会有。自动建组等于替用户改了他看惯的口径。</p>
 */
@Service
@RequiredArgsConstructor
public class AccountGroupService {

    private final AccountGroupMapper groupMapper;
    private final AccountMapper accountMapper;
    private final com.family.finance.repository.PeriodAccountGroupMapper frozenMapper;

    /** 推荐的分组:名称 → 建议成员账户 id */
    public record Suggestion(String name, String why, List<Long> accountIds) {}

    /**
     * 推荐分组。只推荐**判据明确**的两类:
     * <ul>
     *   <li><b>随时可取</b> —— 账户的流动性分层已经是 {@code LIQUID},不是猜的</li>
     *   <li><b>负债</b> —— 账户类型自己说了算</li>
     * </ul>
     * 不做「看起来像一类」这种模糊推荐 —— 猜错了用户根本发现不了,而这会影响收益口径。
     */
    public List<Suggestion> suggestions(long familyId) {
        List<Long> liquid = new ArrayList<>();
        List<Long> liability = new ArrayList<>();
        for (Account a : accountMapper.findActiveByFamily(familyId)) {
            if (a.getType() != null && a.getType().isLiability()) { liability.add(a.getId()); continue; }
            if (a.getLiquidity() == AccountLiquidity.LIQUID) liquid.add(a.getId());
        }
        List<Suggestion> out = new ArrayList<>();
        if (liquid.size() >= 2) out.add(new Suggestion("随时可取", "流动性分层已判定为「随时可取」", liquid));
        if (liability.size() >= 2) out.add(new Suggestion("负债", "账户类型属于负债类", liability));
        return out;
    }

    /**
     * 建组时的代价提示(FR-470)。**不拦**,只如实说 ——
     * 限制用户的东西我们经常猜错;但不告知代价是另一回事。
     */
    public String costWarning(long familyId, List<Long> accountIds) {
        if (accountIds == null || accountIds.size() < 2) return null;
        Map<Long, Account> byId = new LinkedHashMap<>();
        accountMapper.findActiveByFamily(familyId).forEach(a -> byId.put(a.getId(), a));
        java.util.Set<String> types = new java.util.LinkedHashSet<>();
        java.util.Set<String> currencies = new java.util.LinkedHashSet<>();
        for (Long id : accountIds) {
            Account a = byId.get(id);
            if (a == null) continue;
            if (a.getType() != null) types.add(a.getType().getLabel());
            if (a.getCurrency() != null) currencies.add(a.getCurrency());
        }
        List<String> notes = new ArrayList<>();
        if (types.size() > 1) {
            notes.add("这个组里混了 " + String.join(" / ", types)
                    + " —— 它的收益率会混口径(比如现金几乎不产生收益,会把股票那部分的年化摊薄)");
        }
        if (currencies.size() > 1) {
            // v1.20 · 跨币种组的组内划转,两端按该期汇率快照换算到视图币种,
            //   若快照汇率与实际成交汇率不同会留下残差。这不是本版引入的 ——
            //   今天全家层面的划转抵消已有同一个问题。如实说,不在事实层编一个汇率去配平。
            notes.add("这个组里混了 " + String.join(" / ", currencies)
                    + " —— 组内跨币种转账在换算成视图币种时会留下少量汇率残差,数字可能不会正好抵消为 0");
        }
        return notes.isEmpty() ? null : String.join(";", notes) + "。还是可以建,只是你得知道这个数该怎么读。";
    }

    @Transactional
    public AccountGroup create(long familyId, Long memberId, String name, String note, List<Long> accountIds) {
        String safe = name == null || name.isBlank() ? "未命名分组" : name.trim();
        AccountGroup g = AccountGroup.builder()
                .familyId(familyId).name(safe).note(note).createdBy(memberId).build();
        groupMapper.insert(g);
        if (accountIds != null) {
            // addMember 是 ON DUPLICATE KEY UPDATE group_id —— 账户已在别的组里就是**搬过来**。
            // 这正是「一个账户最多属一个组」在写入侧的自然表达,不需要先查再判。
            for (Long id : accountIds) groupMapper.addMember(g.getId(), id);
        }
        backfillFreeze(familyId);
        return g;
    }

    /**
     * v1.20 · 给「已关账但还没有定格行」的期补一次定格。
     *
     * <p>只补<b>一次</b>:补过的期就有行了,后续改组成员不会再动它们 ——
     * 这正是 FR-468「已关账月份的历史数字不会因为今天改了组而变」成立的前提。</p>
     *
     * <p>不补会怎样:解析器回落到当前成员关系,于是每改一次组,12 期趋势图变一次。
     * 每个数字自身都算对了,但用户会当成算错了 —— 这类错误不报错、不降级。</p>
     */
    private void backfillFreeze(long familyId) {
        for (Long pid : frozenMapper.closedPeriodsWithoutFreeze(familyId)) {
            frozenMapper.freezeByPeriod(familyId, pid);
        }
    }

    @Transactional
    public void updateMembers(long familyId, long groupId, String name, List<Long> accountIds) {
        AccountGroup g = groupMapper.findById(familyId, groupId);
        if (g == null) throw new IllegalArgumentException("分组不存在");
        if (name != null && !name.isBlank()) {
            g.setName(name.trim());
            groupMapper.rename(g);
        }
        groupMapper.clearMembers(groupId);
        if (accountIds != null) for (Long id : accountIds) groupMapper.addMember(groupId, id);
        backfillFreeze(familyId);   // 只对【还没定格】的期生效,已定格的一行不动
    }

    /** 删组 —— 成员关系随外键级联删除;**账户与历史数据一行不动**(FR-463) */
    @Transactional
    public void delete(long familyId, long groupId) {
        // 先删历史定格,再删组本身 —— 顺序重要:组没了之后就查不到它的定格了。
        // 删组是这一版唯一的退路(PRD §11),它必须真的退干净:
        // 不删定格的话,历史里会永远留着一个已经不存在的组名,而用户没有办法退回去。
        frozenMapper.deleteByGroup(groupId);
        groupMapper.delete(familyId, groupId);
    }

    public List<AccountGroup> list(long familyId) { return groupMapper.findByFamily(familyId); }

    public Map<Long, List<Long>> membersByGroup(long familyId) {
        Map<Long, List<Long>> out = new LinkedHashMap<>();
        for (var m : groupMapper.findMembersByFamily(familyId)) {
            out.computeIfAbsent(m.groupId(), k -> new ArrayList<>()).add(m.accountId());
        }
        return out;
    }
}
