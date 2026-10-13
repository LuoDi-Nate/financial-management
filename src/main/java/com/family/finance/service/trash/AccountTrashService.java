package com.family.finance.service.trash;

import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.ledger.PrincipalAdjustment;
import com.family.finance.domain.trash.AccountTrash;
import com.family.finance.repository.AccountTrashMapper;
import com.family.finance.repository.PrincipalAdjustmentMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.lens.LensStaleEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * v1.33 · 删除(移进回收站)/ 恢复 / 彻底清除 —— 各一个事务(PRD FR-1220 ~ 1234 · tech-design 选型一 / 二)。
 *
 * <p>删除:按 {@link AccountTrashSpec#TABLES} 把这个账户在每张表里的行原样存下来再删;
 * 和别的账户之间的每笔划转,按计划给对方写一笔本金进出(对方收益一分不变);
 * 别处指向它的引用(别的贷款的默认还款来源、别的待填行指向它的划转)置空并记下。
 * 恢复按清单逆序原样插回,逐条检查依赖,放不回的列出来、不静默跳过。</p>
 */
@Service
@RequiredArgsConstructor
public class AccountTrashService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final TypeReference<LinkedHashMap<String, List<LinkedHashMap<String, Object>>>> PAYLOAD = new TypeReference<>() {};

    private final AccountTrashMapper trashMapper;
    private final PrincipalAdjustmentMapper principalMapper;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    /** 改记与置空的记录(恢复时还原) */
    public record Rewrites(List<PaRewrite> pa, Map<Long, Long> paymentSources, Map<Long, Long> todoRefs) {
    }

    public record PaRewrite(long id, long transferId, long counterpartId, String counterpartName) {
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    @Transactional
    public AccountTrash delete(long familyId, long memberId, AccountDeletePlanner.DeletePlan plan, String backupFile) {
        long accountId = plan.account().getId();
        String name = plan.account().getDisplayName();

        // ① 原行存下来(SELECT * 原样;MyBatis 的 Map 不带空值的列,按当前表结构补成 null —— 恢复时才不会被默认值顶掉)
        Map<String, List<Map<String, Object>>> payload = new LinkedHashMap<>();
        for (AccountTrashSpec.Table t : AccountTrashSpec.TABLES) {
            List<String> cols = trashMapper.columnsOf(t.table());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : trashMapper.selectRows(t.table(), t.where(), familyId, accountId)) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String c : cols) row.put(c, plainValue(r.get(c)));
                rows.add(row);
            }
            payload.put(t.table(), rows);
        }

        // ② 对方那一头改记成本金进出(只按计划里的划转清单 —— 面板上写了几笔就改几笔)
        List<PaRewrite> pa = new ArrayList<>();
        for (AccountDeletePlanner.TransferImpact ti : plan.transfers()) {
            if (AccountDeletePlanner.OPENING.equals(ti.mode())) continue;   // 对方第一期:并进它的开账基线
            BigDecimal amount = AccountDeletePlanner.PA_IN.equals(ti.mode()) ? ti.amount() : ti.amount().negate();
            PrincipalAdjustment row = PrincipalAdjustment.builder()
                    .accountId(ti.counterpart().getId()).periodId(ti.period().getId()).amount(amount)
                    .note("删除账户「" + name + "」时由划转改记(" + ti.directionLabel() + " " + ti.amountLabel() + ")")
                    .memberId(memberId).build();
            if (principalMapper.insertSystem(familyId, row) != 1) {
                throw new IllegalStateException("改记「" + ti.counterpart().getDisplayName() + "」那一笔没写上,整个删除已撤回");
            }
            pa.add(new PaRewrite(row.getId(), ti.transferId(), ti.counterpart().getId(), ti.counterpart().getDisplayName()));
        }

        // ③ 别处指向它的引用:别的贷款的默认还款来源(D9)、别的待填行指向它的划转(外键)
        Map<Long, Long> paymentSources = new LinkedHashMap<>();
        for (Long loanId : trashMapper.loansPayingFrom(familyId, accountId)) {
            trashMapper.setPaymentSource(familyId, loanId, null);
            paymentSources.put(loanId, accountId);
        }
        Map<Long, Long> todoRefs = new LinkedHashMap<>();
        for (String ref : trashMapper.todoRefsToTransfersOf(familyId, accountId)) {
            String[] kv = ref.split(":");
            long todoId = Long.parseLong(kv[0]);
            todoRefs.put(todoId, Long.parseLong(kv[1]));
            trashMapper.setTodoTransfer(familyId, todoId, null);
        }

        // ④ AI 存稿(D6):涉及的月份的复盘、调仓建议 —— 下次打开重新生成
        if (!plan.periodIds().isEmpty()) trashMapper.clearReviewCache(familyId, plan.periodIds());
        trashMapper.clearAdviceCache(familyId);

        // ⑤ 按外键安全的顺序删
        int accountRows = 0;
        for (AccountTrashSpec.Table t : AccountTrashSpec.TABLES) {
            int n = trashMapper.deleteRows(t.table(), t.where(), familyId, accountId);
            if ("account".equals(t.table())) accountRows = n;
        }
        if (accountRows != 1) throw new IllegalStateException("账户没删掉(可能已经被删过),整个删除已撤回");

        // ⑥ 回收站一行 + 审计
        AccountTrash trash = AccountTrash.builder()
                .accountId(accountId).accountName(name).accountType(plan.account().getType().name())
                .periodIds(plan.periodIds().stream().map(String::valueOf).collect(Collectors.joining(",")))
                .rangeLabel(plan.rangeLabel()).closedCount(plan.closedCount())
                .transferCount(plan.transfers().size()).counterpartCount(plan.counterpartCount())
                .summary(clip(plan.movedSummary() + " · 划转 " + plan.transferRowCount() + " 笔", 500))
                .payload(write(payload)).rewrites(write(new Rewrites(pa, paymentSources, todoRefs)))
                .backupFile(backupFile).deletedBy(memberId).build();
        trashMapper.insertOwned(familyId, trash);
        auditLogService.record(familyId, memberId, AuditLogType.ACCOUNT_DELETE, "account", accountId,
                clip("删除账户「" + name + "」→ 回收站 #" + trash.getId() + " · " + (plan.rangeLabel() == null ? "无历史" : "改写 " + plan.rangeLabel())
                        + " · " + pa.size() + " 笔划转改记成本金进出 · " + (backupFile == null ? "未备份(用户选了不备份直接删除)" : "备份 " + backupFile), 255));
        events.publishEvent(new LensStaleEvent(familyId));
        return trash;
    }

    // ── 恢复 ────────────────────────────────────────────────────────────────

    public record RestoreResult(String accountName, int rows, List<String> notes) {
    }

    @Transactional
    public RestoreResult restore(long familyId, long memberId, long trashId) {
        AccountTrash t = trashMapper.findFull(familyId, trashId).orElseThrow(() -> new IllegalArgumentException("回收站里没有这一项"));
        if (!t.isActive() || t.getPayload() == null) throw new IllegalStateException("「" + t.getAccountName() + "」已经恢复过或彻底清除了");
        if (trashMapper.accountExists(familyId, t.getAccountId()) > 0) throw new IllegalStateException("这个账户已经在了");
        Map<String, List<LinkedHashMap<String, Object>>> payload = read(t.getPayload(), PAYLOAD);
        Rewrites rw = t.getRewrites() == null ? new Rewrites(List.of(), Map.of(), Map.of()) : read(t.getRewrites(), new TypeReference<Rewrites>() {});
        Map<Long, String> paName = rw.pa().stream().collect(Collectors.toMap(PaRewrite::transferId, PaRewrite::counterpartName, (a, b) -> a));

        List<String> notes = new ArrayList<>();
        Map<Long, Boolean> periodOk = new HashMap<>();
        Set<Long> restoredTransfers = new HashSet<>();
        int rows = 0;
        List<AccountTrashSpec.Table> reversed = new ArrayList<>(AccountTrashSpec.TABLES);
        java.util.Collections.reverse(reversed);
        for (AccountTrashSpec.Table spec : reversed) {
            List<LinkedHashMap<String, Object>> list = payload.getOrDefault(spec.table(), List.of());
            if (list.isEmpty()) continue;
            Set<String> current = new HashSet<>(trashMapper.columnsOf(spec.table()));
            for (LinkedHashMap<String, Object> row : list) {
                String why = blocker(familyId, spec.table(), row, t.getAccountId(), periodOk, restoredTransfers, paName);
                if (why != null) {
                    notes.add(why);
                    continue;
                }
                List<String> cols = new ArrayList<>();
                List<Object> vals = new ArrayList<>();
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    if (!current.contains(e.getKey())) continue;   // 表后来少了这一列
                    cols.add(e.getKey());
                    vals.add(e.getValue());
                }
                trashMapper.insertRow(spec.table(), cols, vals);
                rows++;
                if ("transfer".equals(spec.table())) restoredTransfers.add(asLong(row.get("id")));
            }
        }

        // 改记写的本金进出:对应的划转还原了 → 拿掉;没还原(对方不在了)→ 保留,对方收益仍不变
        for (PaRewrite p : rw.pa()) {
            if (restoredTransfers.contains(p.transferId())) principalMapper.deleteSystem(familyId, p.id());
        }
        // 别的贷款的默认还款来源 / 别的待填行指向的划转:还空着、还在的才还原
        for (Map.Entry<Long, Long> e : rw.paymentSources().entrySet()) {
            if (trashMapper.accountExists(familyId, e.getKey()) > 0) trashMapper.restorePaymentSource(familyId, e.getKey(), e.getValue());
        }
        for (Map.Entry<Long, Long> e : rw.todoRefs().entrySet()) {
            if (restoredTransfers.contains(e.getValue())) trashMapper.setTodoTransfer(familyId, e.getKey(), e.getValue());
        }

        List<String> uniq = notes.stream().distinct().toList();
        String noteText = uniq.isEmpty() ? null : clip(String.join(";", uniq), 1000);
        if (trashMapper.markRestored(familyId, trashId, memberId, noteText) != 1) {
            throw new IllegalStateException("恢复没成功(可能刚被别人恢复或清除),整次已撤回");
        }
        auditLogService.record(familyId, memberId, AuditLogType.ACCOUNT_TRASH_RESTORE, "account", t.getAccountId(),
                clip("从回收站恢复账户「" + t.getAccountName() + "」· 放回 " + rows + " 行"
                        + (uniq.isEmpty() ? "" : " · 没放回:" + String.join(";", uniq)), 255));
        events.publishEvent(new LensStaleEvent(familyId));
        return new RestoreResult(t.getAccountName(), rows, uniq);
    }

    /** 这一行能不能放回去;不能 → 返回给人看的原因 */
    private String blocker(long familyId, String table, Map<String, Object> row, long accountId,
                           Map<Long, Boolean> periodOk, Set<Long> restoredTransfers, Map<Long, String> paName) {
        Long pid = asLong(row.get("period_id"));
        if (pid != null && !periodOk.computeIfAbsent(pid, k -> trashMapper.periodExists(familyId, k) > 0)) {
            return "账期 #" + pid + " 已经不在了,那一期的数据没放回";
        }
        switch (table) {
            case "account" -> {
                Long src = asLong(row.get("default_payment_source_account_id"));
                if (src != null && trashMapper.accountExists(familyId, src) == 0) {
                    row.put("default_payment_source_account_id", null);
                    return null;   // 只清掉这一列,账户照放回
                }
            }
            case "transfer" -> {
                Long from = asLong(row.get("from_account_id"));
                Long to = asLong(row.get("to_account_id"));
                long other = Objects.equals(from, accountId) ? to : from;
                if (trashMapper.accountExists(familyId, other) == 0) {
                    String n = paName.getOrDefault(asLong(row.get("id")), "对方账户");
                    return "和「" + n + "」之间的划转没还原(对方账户已经不在了),对方那头的本金进出保留";
                }
            }
            case "snapshot_todo" -> {
                Long tr = asLong(row.get("prefilled_transfer_id"));
                if (tr != null && trashMapper.transferExists(familyId, tr) == 0) row.put("prefilled_transfer_id", null);
            }
            case "account_group_member" -> {
                if (trashMapper.groupExists(familyId, asLong(row.get("group_id"))) == 0) return "原来的账户分组已经删了,没放回分组";
            }
            case "goal_account" -> {
                if (trashMapper.goalExists(familyId, asLong(row.get("goal_id"))) == 0) return "绑定的目标已经删了,这条绑定没放回";
            }
            case "rebalance_plan_item" -> {
                Long from = asLong(row.get("from_account_id"));
                Long to = asLong(row.get("to_account_id"));
                long other = Objects.equals(from, accountId) ? (to == null ? accountId : to) : (from == null ? accountId : from);
                if (trashMapper.planExists(familyId, asLong(row.get("plan_id"))) == 0 || trashMapper.accountExists(familyId, other) == 0) {
                    return "再平衡计划(或计划里的另一个账户)已经不在了,计划条目没放回";
                }
            }
            case "fixed_flow" -> {
                for (String c : List.of("account_id", "target_account_id")) {
                    Long id = asLong(row.get(c));
                    if (id != null && id != accountId && trashMapper.accountExists(familyId, id) == 0 && row.get("disabled_at") == null) {
                        row.put("disabled_at", LocalDateTime.now().format(DT));
                        row.put("disabled_reason", "ACCOUNT_ARCHIVED");
                    }
                }
            }
            default -> { }
        }
        return null;
    }

    // ── 彻底清除 ────────────────────────────────────────────────────────────

    @Transactional
    public String purge(long familyId, long memberId, long trashId) {
        AccountTrash t = trashMapper.findFull(familyId, trashId).orElseThrow(() -> new IllegalArgumentException("回收站里没有这一项"));
        if (trashMapper.markPurged(familyId, trashId, memberId) != 1) {
            throw new IllegalStateException("「" + t.getAccountName() + "」已经恢复过或彻底清除了");
        }
        auditLogService.record(familyId, memberId, AuditLogType.ACCOUNT_TRASH_PURGE, "account", t.getAccountId(),
                "彻底清除回收站里的账户「" + t.getAccountName() + "」(之后只能从备份恢复)");
        return t.getAccountName();
    }

    /** 彻底清除要打的那句话 */
    public static String purgePhrase(String accountName) {
        return "彻底清除「" + accountName + "」";
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    /** JDBC 读出来的值 → JSON 里存得住、插回 MySQL 认得的样子 */
    static Object plainValue(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime d) return d.format(DT);
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().format(DT);
        if (v instanceof LocalDate d) return d.toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof LocalTime d) return d.toString();
        if (v instanceof TemporalAccessor) return v.toString().replace('T', ' ');
        if (v instanceof BigDecimal b) return b.toPlainString();
        if (v instanceof Boolean || v instanceof Number || v instanceof String) return v;
        if (v instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        return v.toString();
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("回收站内容序列化失败:" + e.getMessage(), e);
        }
    }

    private <T> T read(String s, TypeReference<T> type) {
        try {
            return json.readValue(s, type);
        } catch (Exception e) {
            throw new IllegalStateException("回收站内容读不出来:" + e.getMessage(), e);
        }
    }

    private static String clip(String s, int n) {
        return s == null ? null : s.length() > n ? s.substring(0, n) : s;
    }
}
