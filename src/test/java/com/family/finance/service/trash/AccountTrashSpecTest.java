package com.family.finance.service.trash;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.33 · 搬运清单的规矩:每条条件都在过滤位置带家庭;账户行最后删、最先放回;
 * 库里挂账户的列(2026-10-12 实测的 24 处 + 回收站自己)每一处都有去处。
 * 将来再加一张挂账户的表:qa-run 的 v133-TRASH-COVERS-SCHEMA 按 information_schema 现查,会先红在那里。
 */
class AccountTrashSpecTest {

    @Test
    void everyConditionFiltersByFamily() {
        for (AccountTrashSpec.Table t : AccountTrashSpec.TABLES) {
            assertThat(t.where()).as(t.table()).containsPattern("family_id = #\\{familyId\\}");
            assertThat(t.where()).as(t.table()).contains("#{accountId}");
            assertThat(t.table()).as("表名只许是字母下划线(通用搬运用 ${table} 拼进 SQL)").matches("[a-z_]+");
        }
    }

    @Test
    void accountRowIsDeletedLast_soForeignKeysNeverBlock() {
        List<AccountTrashSpec.Table> ts = AccountTrashSpec.TABLES;
        assertThat(ts.get(ts.size() - 1).table()).isEqualTo("account");
        List<String> names = ts.stream().map(AccountTrashSpec.Table::table).toList();
        // 子在父前:确认记录在固定收支前、截图明细在截图前、拆分在持仓前、待填在划转前(待填行有外键指向划转)
        assertThat(names.indexOf("fixed_flow_occurrence")).isLessThan(names.indexOf("fixed_flow"));
        assertThat(names.indexOf("holding_import_item")).isLessThan(names.indexOf("holding_import"));
        assertThat(names.indexOf("holding_allocation")).isLessThan(names.indexOf("stock_holding"));
        assertThat(names.indexOf("snapshot_todo")).isLessThan(names.indexOf("transfer"));
        assertThat(Set.copyOf(names)).hasSize(names.size());
    }

    @Test
    void everyAccountColumnInTheSchemaHasAHome() {
        // 2026-10-12 beta 库 information_schema 里所有 *account_id* 列
        List<String> schema = List.of(
                "account.default_payment_source_account_id", "account_group_member.account_id",
                "account_insurance_policy.account_id", "broker_link.account_id", "broker_link.broker_account_id",
                "cash_flow.account_id", "expense_account_rule.account_id", "expense_import_batch.account_id",
                "fixed_flow.account_id", "fixed_flow.target_account_id", "goal_account.account_id",
                "holding_import.account_id", "holding_share_event.account_id", "period_account_attr.account_id",
                "period_account_group.account_id", "period_snapshot.account_id", "principal_adjustment.account_id",
                "rebalance_plan_item.from_account_id", "rebalance_plan_item.to_account_id", "snapshot_todo.account_id",
                "stock_holding.account_id", "stock_valuation_event.account_id", "transfer.from_account_id",
                "transfer.to_account_id", "account_trash.account_id");
        assertThat(AccountTrashSpec.COLUMNS.keySet()).containsAll(schema);
    }
}
