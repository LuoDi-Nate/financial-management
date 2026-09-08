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
        List<Long> cash = new ArrayList<>();
        for (Account a : accountMapper.findActiveByFamily(familyId)) {
            if (a.getType() != null && a.getType().isLiability()) { liability.add(a.getId()); continue; }
            if (a.getType() == com.family.finance.domain.account.AccountType.CASH) cash.add(a.getId());
            if (a.getLiquidity() == AccountLiquidity.LIQUID) liquid.add(a.getId());
        }
        List<Suggestion> out = new ArrayList<>();
        /* v1.20 · 「日常周转」放第一个 —— 这才是 issue #17 提出者真正要的那一组。
         *
         * 他的原话:「我实际有非常多个活期账户,有不同银行卡的、有微信的、有支付宝的,
         *   经常会需要在这些不同的活期账户中转账……不可能把这些划转全部有效地输入进去」。
         *
         * 为什么不用「随时可取」顶替:那是**流动性分层**(LIQUID),把货币基金类理财也算进来了 ——
         * 而他说的「活期账户」指的是**互相转账的那些钱包**:银行卡 / 微信 / 支付宝。
         * 货基和它们之间很少来回划转,并进来只会让这个组的收益口径变糊。
         * 两个都留着,让用户自己挑;判据都写在卡片上。 */
        if (cash.size() >= 2) {
            out.add(new Suggestion("日常周转",
                    "银行卡 / 微信 / 支付宝这类互相转账频繁的现金账户 —— 划转在组内自动抵消,不再是噪声", cash));
        }
        if (liquid.size() >= 2) {
            out.add(new Suggestion("随时可取",
                    "流动性分层判定为「随时可取」(比「日常周转」多含货币基金类理财)", liquid));
        }
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

    /**
     * 业务异常 —— 用户能看懂、且<b>是他自己能纠正</b>的问题。
     *
     * <p>与它相对的是 {@code SQLIntegrityConstraintViolationException} 冒成 500 白页:
     * 那种页面除了「出错了」什么都没说,用户既不知道哪里错了,也不知道该改什么。
     * v1.20 第一版就是这样 —— 组名重一次就白页(维护者验收时当场撞上)。</p>
     */
    public static class GroupConflictException extends RuntimeException {
        public GroupConflictException(String message) { super(message); }
    }

    @Transactional
    public AccountGroup create(long familyId, Long memberId, String name, String note, List<Long> accountIds) {
        String safe = name == null || name.isBlank() ? "未命名分组" : name.trim();
        requireNameFree(familyId, safe, null);
        requireAccountsFree(familyId, accountIds, null);
        AccountGroup g = AccountGroup.builder()
                .familyId(familyId).name(safe).note(note).createdBy(memberId).build();
        groupMapper.insert(g);
        if (accountIds != null) {
            for (Long id : accountIds) groupMapper.addMember(g.getId(), id);
        }
        backfillFreeze(familyId);
        return g;
    }

    /** 组名不许重 —— 重了 DB 会抛唯一约束,那是 500 白页,不是给用户看的东西 */
    private void requireNameFree(long familyId, String name, Long selfId) {
        for (AccountGroup g : groupMapper.findByFamily(familyId)) {
            if (g.getName().equals(name) && (selfId == null || !g.getId().equals(selfId))) {
                throw new GroupConflictException("已经有一个叫「" + name + "」的分组了,换个名字。");
            }
        }
    }

    /**
     * 账户不许被两个组同时选中。
     *
     * <p><b>为什么是「拒绝」而不是「搬过来」</b>:第一版用 {@code ON DUPLICATE KEY UPDATE}
     * 实现成了搬家语义 —— 于是在新组里勾一个已属别组的账户,系统会<b>悄悄把它从原组挪走</b>,
     * 页面一个字都不说。原组的收益口径当场就变了,而用户不知道。
     * <b>静默地改掉用户没打算改的东西,比报错更糟。</b></p>
     *
     * <p>界面上这类账户是置灰的;能走到这里说明是绕过界面直接调的接口 ——
     * 那也该得到一句人话,而不是一串约束名。</p>
     */
    private void requireAccountsFree(long familyId, List<Long> accountIds, Long selfId) {
        if (accountIds == null || accountIds.isEmpty()) return;
        Map<Long, String> owner = occupiedBy(familyId, selfId);
        for (Long id : accountIds) {
            String by = owner.get(id);
            if (by != null) {
                String name = accountMapper.findById(id).map(Account::getDisplayName).orElse("账户#" + id);
                throw new GroupConflictException(
                        "「" + name + "」已经在分组「" + by + "」里了。一个账户只能属于一个分组 —— "
                        + "要把它挪过来,先去「" + by + "」里把它取消勾选。");
            }
        }
    }

    /**
     * 已被占用的账户 → 占用它的分组名。
     *
     * @param exceptGroupId 编辑某个组时把它自己排除掉,否则它自己的成员会被判成「被占用」
     */
    public Map<Long, String> occupiedBy(long familyId, Long exceptGroupId) {
        Map<Long, String> groupName = new LinkedHashMap<>();
        groupMapper.findByFamily(familyId).forEach(g -> groupName.put(g.getId(), g.getName()));
        Map<Long, String> out = new LinkedHashMap<>();
        for (var m : groupMapper.findMembersByFamily(familyId)) {
            if (exceptGroupId != null && exceptGroupId.equals(m.groupId())) continue;
            out.put(m.accountId(), groupName.getOrDefault(m.groupId(), "别的分组"));
        }
        return out;
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
        if (name != null && !name.isBlank()) requireNameFree(familyId, name.trim(), groupId);
        requireAccountsFree(familyId, accountIds, groupId);
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
