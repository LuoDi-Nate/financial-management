package com.family.finance.service.export;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.family.Family;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * 单账户账本 CSV 导出 · v0.2 FR-31
 *
 * 行:每月一行,字段
 *   月份, 期初, 入账, 出账, 转入, 转出, 期末, 期间损益, 累计损益
 * 全部用账户原币种,数值精度 2 位小数。Excel 友好(BOM + UTF-8)。
 */
@Service
@RequiredArgsConstructor
public class LedgerExporter {

    private final AccountService accountService;
    private final FactViewService factViewService;
    private final FamilyMapper familyMapper;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    public void writeAccountLedger(long familyId, long accountId, OutputStream out) throws IOException {
        Account account = accountService.require(familyId, accountId);
        Family family = familyMapper.findById(familyId)
                .orElseThrow(() -> new IllegalArgumentException("家庭不存在"));

        // 拉过去 12 个月
        LocalDate end = LocalDate.now().withDayOfMonth(1);
        LocalDate start = end.minusMonths(11);
        FactSlice slice = factViewService.load(new FactFilter(
                familyId, family.getPeriodType(), start, end,
                true, List.of(accountId), family.getBaseCurrency()));

        List<AccountPeriodFact> rows = slice.rows().stream()
                .filter(r -> r.accountId().equals(accountId))
                .sorted(Comparator.comparing(AccountPeriodFact::periodStart))
                .toList();

        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write('﻿'); // BOM
        // v1.30 · 「补录本金」加在最后一列:已有列的位置不动,按列号读这份文件的脚本不受影响
        w.write("月份,期初,入账,出账,转入,转出,期末,期间损益,累计损益,补录本金\n");

        BigDecimal cumulative = BigDecimal.ZERO;
        for (AccountPeriodFact r : rows) {
            BigDecimal pnl = r.periodPnlOrig() == null ? BigDecimal.ZERO : r.periodPnlOrig();
            cumulative = cumulative.add(pnl);
            w.write(MONTH.format(r.periodStart()));
            w.write(',');
            w.write(d(r.previousEndBalanceOrig()));
            w.write(',');
            w.write(d(r.incomeOrig()));
            w.write(',');
            w.write(d(r.expenseOrig()));
            w.write(',');
            w.write(d(r.transferInOrig()));
            w.write(',');
            w.write(d(r.transferOutOrig()));
            w.write(',');
            w.write(d(r.endBalanceOrig()));
            w.write(',');
            w.write(d(pnl));
            w.write(',');
            w.write(d(cumulative));
            w.write(',');
            w.write(d(r.principalAdjOrig()));
            w.write('\n');
        }
        w.flush();
    }

    private static String d(BigDecimal v) {
        if (v == null) return "";
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
