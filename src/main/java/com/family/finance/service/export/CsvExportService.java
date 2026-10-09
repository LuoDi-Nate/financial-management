package com.family.finance.service.export;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.flow.CashFlow;
import com.family.finance.domain.fx.FxRate;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.domain.transfer.Transfer;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.FxMapper;
import com.family.finance.service.member.MemberDirectory;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.repository.TransferMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PRD FR-16:一键 CSV 导出。
 * 打包 8 张表 + README.txt 为 ZIP;每个 CSV 用 UTF-8 BOM 让 Excel 识别中文。
 */
@Service
@RequiredArgsConstructor
public class CsvExportService {
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FamilyMapper familyMapper;
    /** v1.15 FR-382 · 名字映射走名录(含已归档)—— 归档一个人,不该让历史数据里的他变成无名氏 */
    private final MemberDirectory memberDirectory;
    private final AccountMapper accountMapper;
    private final PeriodMapper periodMapper;
    private final SnapshotMapper snapshotMapper;
    private final CashFlowMapper cashFlowMapper;
    private final TransferMapper transferMapper;
    private final FxMapper fxMapper;
    /** v1.30 · 补录本金 —— 备份要完整:少了它,导出的数据重算出来的收益会对不上 */
    private final com.family.finance.repository.PrincipalAdjustmentMapper principalAdjustmentMapper;

    public void writeZip(long familyId, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            Family family = familyMapper.findById(familyId)
                    .orElseThrow(() -> new IllegalArgumentException("家庭不存在: " + familyId));

            writeEntry(zip, "families.csv", writer -> {
                line(writer, "id", "name", "brand_text", "logo_path", "logo_preset", "base_currency", "period_type", "created_at", "updated_at");
                line(writer,
                        s(family.getId()), e(family.getName()), e(family.getBrandText()), e(family.getLogoPath()),
                        e(family.getLogoPreset()),
                        e(family.getBaseCurrency()), s(family.getPeriodType()),
                        ts(family.getCreatedAt()), ts(family.getUpdatedAt()));
            });

            List<Member> members = memberDirectory.listAll(familyId);
            writeEntry(zip, "members.csv", writer -> {
                line(writer, "id", "family_id", "username", "display_name", "role_label",
                        "must_change_pw", "archived_at", "last_login_at", "created_at");
                for (Member m : members) {
                    line(writer,
                            s(m.getId()), s(m.getFamilyId()), e(m.getUsername()), e(m.getDisplayName()),
                            e(m.getRoleLabel()), s(m.isMustChangePw()),
                            ts(m.getArchivedAt()), ts(m.getLastLoginAt()), ts(m.getCreatedAt()));
                }
            });

            List<Account> accounts = accountMapper.findAllByFamily(familyId);
            writeEntry(zip, "accounts.csv", writer -> {
                line(writer, "id", "family_id", "template_id", "display_name", "type", "currency",
                        "primary_owner_member_id", "default_payment_source_account_id",
                        "display_order", "archived_at", "created_at");
                for (Account a : accounts) {
                    line(writer,
                            s(a.getId()), s(a.getFamilyId()), s(a.getTemplateId()),
                            e(a.getDisplayName()), s(a.getType()), e(a.getCurrency()),
                            s(a.getPrimaryOwnerMemberId()), s(a.getDefaultPaymentSourceAccountId()),
                            s(a.getDisplayOrder()), ts(a.getArchivedAt()), ts(a.getCreatedAt()));
                }
            });

            List<Period> periods = periodMapper.findAllByFamily(familyId);
            writeEntry(zip, "periods.csv", writer -> {
                line(writer, "id", "family_id", "period_type", "period_start", "period_end",
                        "status", "closed_at", "created_at");
                for (Period p : periods) {
                    line(writer,
                            s(p.getId()), s(p.getFamilyId()), s(p.getPeriodType()),
                            s(p.getPeriodStart()), s(p.getPeriodEnd()), s(p.getStatus()),
                            ts(p.getClosedAt()), ts(p.getCreatedAt()));
                }
            });

            List<PeriodSnapshot> snapshots = snapshotMapper.findAllByFamily(familyId);
            writeEntry(zip, "snapshots.csv", writer -> {
                line(writer, "id", "period_id", "account_id", "end_balance",
                        "submitted_by", "submitted_at", "note");
                for (PeriodSnapshot ss : snapshots) {
                    line(writer,
                            s(ss.getId()), s(ss.getPeriodId()), s(ss.getAccountId()),
                            s(ss.getEndBalance()), s(ss.getSubmittedBy()),
                            ts(ss.getSubmittedAt()), e(ss.getNote()));
                }
            });

            List<CashFlow> flows = cashFlowMapper.findAllByFamily(familyId);
            writeEntry(zip, "cash_flows.csv", writer -> {
                line(writer, "id", "period_id", "account_id", "kind", "category_code",
                        "amount", "occurred_at", "note", "submitted_by", "submitted_at");
                for (CashFlow cf : flows) {
                    line(writer,
                            s(cf.getId()), s(cf.getPeriodId()), s(cf.getAccountId()),
                            s(cf.getKind()), e(cf.getCategoryCode()), s(cf.getAmount()),
                            s(cf.getOccurredAt()), e(cf.getNote()),
                            s(cf.getSubmittedBy()), ts(cf.getSubmittedAt()));
                }
            });

            List<Transfer> transfers = transferMapper.findAllByFamily(familyId);
            writeEntry(zip, "transfers.csv", writer -> {
                // issue #21 · 补 to_amount(转入方实到,转入账户币种)。原来没有这一列 ——
                //   跨币种划转导出之后,「对方实际到账多少」这个信息就丢了,备份不完整。
                //   加在【最后】而不是插在 amount 旁边:已有列的位置一个都不动,按列号读这份文件的脚本不受影响。
                line(writer, "id", "period_id", "from_account_id", "to_account_id", "amount",
                        "occurred_at", "note", "submitted_by", "submitted_at", "is_draft", "to_amount");
                for (Transfer t : transfers) {
                    line(writer,
                            s(t.getId()), s(t.getPeriodId()), s(t.getFromAccountId()), s(t.getToAccountId()),
                            s(t.getAmount()), s(t.getOccurredAt()), e(t.getNote()),
                            s(t.getSubmittedBy()), ts(t.getSubmittedAt()), s(t.isDraft()), s(t.getToAmount()));
                }
            });

            List<FxRate> fx = fxMapper.findAllByFamily(familyId);
            writeEntry(zip, "fx_rates.csv", writer -> {
                line(writer, "id", "family_id", "base_currency", "quote_currency",
                        "period_id", "rate", "source", "fetched_at");
                for (FxRate r : fx) {
                    line(writer,
                            s(r.getId()), s(r.getFamilyId()), e(r.getBaseCurrency()), e(r.getQuoteCurrency()),
                            s(r.getPeriodId()), s(r.getRate()), e(r.getSource()), ts(r.getFetchedAt()));
                }
            });

            // v1.30 · 补录本金(含已删除,带 deleted_at)—— 新文件放最后,已有文件一个字节不动
            var principals = principalAdjustmentMapper.findAllByFamily(familyId);
            writeEntry(zip, "principal_adjustments.csv", writer -> {
                line(writer, "id", "period_id", "account_id", "amount", "holding_id", "note",
                        "source_tag", "member_id", "created_at", "deleted_at");
                for (var pa : principals) {
                    line(writer,
                            s(pa.getId()), s(pa.getPeriodId()), s(pa.getAccountId()), s(pa.getAmount()),
                            s(pa.getHoldingId()), e(pa.getNote()), e(pa.getSourceTag()), s(pa.getMemberId()),
                            ts(pa.getCreatedAt()), ts(pa.getDeletedAt()));
                }
            });

            writeEntry(zip, "README.txt", writer -> {
                writer.write("家庭账房 v0.1 数据导出\n");
                writer.write("导出时间: " + STAMP.format(LocalDateTime.now()) + "\n");
                writer.write("家庭: " + family.getName() + " (id=" + family.getId() + ")\n\n");
                writer.write("9 张 CSV 表格使用 UTF-8 编码,带 BOM,Excel 可直接打开。\n");
                writer.write("文件清单:\n");
                writer.write("  families.csv     家庭基础信息\n");
                writer.write("  members.csv      成员(不含密码哈希)\n");
                writer.write("  accounts.csv     账户(含已归档)\n");
                writer.write("  periods.csv      所有周期\n");
                writer.write("  snapshots.csv    period_snapshot 月末余额\n");
                writer.write("  cash_flows.csv   收入/支出流水\n");
                writer.write("  transfers.csv    跨账户转账(含草稿)\n");
                writer.write("  fx_rates.csv     汇率快照\n");
                writer.write("  principal_adjustments.csv  补录本金(以前就有、某期才补录的钱 · 算本金不算收益)\n");
            });
        }
    }

    @FunctionalInterface
    private interface Block {
        void write(Writer writer) throws IOException;
    }

    private void writeEntry(ZipOutputStream zip, String name, Block block) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(UTF8_BOM);
        Writer writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        block.write(writer);
        writer.flush();
        zip.closeEntry();
    }

    private static void line(Writer writer, Object... cells) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cells[i] == null ? "" : cells[i]);
        }
        sb.append("\r\n");
        writer.write(sb.toString());
    }

    /** 包含逗号/引号/换行的字段加引号转义,纯文本字段直出。 */
    private static String e(Object value) {
        if (value == null) return "";
        String s = value.toString();
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** 数值/枚举/日期等无逗号风险字段的简单 toString。 */
    private static String s(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String ts(LocalDateTime t) {
        return t == null ? "" : STAMP.format(t);
    }
}
