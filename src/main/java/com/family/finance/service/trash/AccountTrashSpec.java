package com.family.finance.service.trash;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.33 · 搬运清单 —— 「一个账户的数据都在哪些表里」<b>唯一</b>的列表(tech-design v1.33 选型一 / 四)。
 *
 * <p>删除按 {@link #TABLES} 的顺序(子 → 父,外键安全)把行原样存进回收站再删;恢复按逆序插回。
 * 每条条件都用 MyBatis 参数 {@code #{familyId}} / {@code #{accountId}},<b>并且都在过滤位置带家庭</b>
 * (直接列,或经 period / account / 父表子查询)—— 单测 {@code AccountTrashSpecTest} 钉住。</p>
 *
 * <p><b>将来再加一张挂账户的表</b>,必须在这里登记(搬走,或在 {@link #COLUMNS} 里写明为什么不搬);
 * 护栏 {@code v133-TRASH-COVERS-SCHEMA} 从库结构里找出所有 {@code *account_id*} 列逐个核对 ——
 * 漏登记的后果是「删了还在算」,不报错。</p>
 */
public final class AccountTrashSpec {

    private AccountTrashSpec() {
    }

    /** 表名 + 选出「属于这个账户的行」的条件(别名 t) */
    public record Table(String table, String where) {
    }

    private static final String IN_FAMILY_PERIODS = "t.period_id IN (SELECT p.id FROM period p WHERE p.family_id = #{familyId})";
    private static final String OWN_ACCOUNT = "EXISTS (SELECT 1 FROM account x WHERE x.id = t.account_id AND x.family_id = #{familyId})";

    /** 删除顺序(子 → 父);恢复用逆序 */
    public static final List<Table> TABLES = List.of(
            new Table("fixed_flow_occurrence", "t.family_id = #{familyId} AND t.fixed_flow_id IN (SELECT f.id FROM fixed_flow f WHERE f.family_id = #{familyId} AND (f.account_id = #{accountId} OR f.target_account_id = #{accountId}))"),
            new Table("fixed_flow", "t.family_id = #{familyId} AND (t.account_id = #{accountId} OR t.target_account_id = #{accountId})"),
            new Table("holding_import_item", "t.import_id IN (SELECT i.id FROM holding_import i WHERE i.family_id = #{familyId} AND i.account_id = #{accountId})"),
            new Table("holding_import", "t.family_id = #{familyId} AND t.account_id = #{accountId}"),
            new Table("holding_allocation", "t.holding_id IN (SELECT h.id FROM stock_holding h JOIN account x ON x.id = h.account_id WHERE x.family_id = #{familyId} AND h.account_id = #{accountId})"),
            new Table("holding_share_event", "t.family_id = #{familyId} AND t.account_id = #{accountId}"),
            new Table("stock_valuation_event", "t.family_id = #{familyId} AND t.account_id = #{accountId}"),
            new Table("stock_holding", "t.account_id = #{accountId} AND " + OWN_ACCOUNT),
            new Table("principal_adjustment", "t.family_id = #{familyId} AND t.account_id = #{accountId}"),
            new Table("cash_flow", "t.account_id = #{accountId} AND " + IN_FAMILY_PERIODS),
            new Table("snapshot_todo", "t.account_id = #{accountId} AND " + IN_FAMILY_PERIODS),
            new Table("period_snapshot", "t.account_id = #{accountId} AND " + IN_FAMILY_PERIODS),
            new Table("period_account_attr", "t.account_id = #{accountId} AND " + IN_FAMILY_PERIODS),
            new Table("period_account_group", "t.account_id = #{accountId} AND " + IN_FAMILY_PERIODS),
            new Table("transfer", "(t.from_account_id = #{accountId} OR t.to_account_id = #{accountId}) AND " + IN_FAMILY_PERIODS),
            new Table("account_group_member", "t.account_id = #{accountId} AND t.group_id IN (SELECT g.id FROM account_group g WHERE g.family_id = #{familyId})"),
            new Table("account_insurance_policy", "t.account_id = #{accountId} AND " + OWN_ACCOUNT),
            new Table("broker_link", "t.account_id = #{accountId} AND " + OWN_ACCOUNT),
            new Table("goal_account", "t.account_id = #{accountId} AND t.goal_id IN (SELECT g.id FROM family_goal g WHERE g.family_id = #{familyId})"),
            new Table("rebalance_plan_item", "(t.from_account_id = #{accountId} OR t.to_account_id = #{accountId}) AND t.plan_id IN (SELECT r.id FROM rebalance_plan r WHERE r.family_id = #{familyId})"),
            new Table("expense_account_rule", "t.family_id = #{familyId} AND t.account_id = #{accountId}"),
            // 整批都在它身上才搬;批里还有别的账户的流水 → 批次元数据留着(撤销那一批时按每笔自己的账户加回)
            new Table("expense_import_batch", "t.family_id = #{familyId} AND t.account_id = #{accountId} AND NOT EXISTS (SELECT 1 FROM cash_flow c WHERE c.import_batch_id = t.id AND c.account_id <> #{accountId})"),
            new Table("account", "t.id = #{accountId} AND t.family_id = #{familyId}")
    );

    /**
     * 库里每一个挂账户 id 的「表.列」去哪儿了(护栏按 information_schema 逐个核对,一个都不许缺)。
     * 值是给人看的一句话。
     */
    public static final Map<String, String> COLUMNS = new LinkedHashMap<>();

    static {
        for (Table t : TABLES) {
            for (String col : List.of("account_id", "target_account_id", "from_account_id", "to_account_id")) {
                if (t.where().contains("t." + col + " ")) COLUMNS.put(t.table() + "." + col, "搬进回收站");
            }
        }
        COLUMNS.put("account.default_payment_source_account_id", "别的贷款把它设成默认还款来源的,置空;恢复时还原(它自己的这一列随账户行一起搬)");
        COLUMNS.put("broker_link.broker_account_id", "券商那边的账号,不是本系统的账户 id");
        COLUMNS.put("account_trash.account_id", "回收站自己");
    }
}
