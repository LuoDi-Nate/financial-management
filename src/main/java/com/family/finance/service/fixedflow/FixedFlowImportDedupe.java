package com.family.finance.service.fixedflow;

import com.family.finance.repository.FixedFlowMapper;
import com.family.finance.service.expense.imports.BillCategoryResolver;
import com.family.finance.service.expense.imports.BillCategoryResolver.Bucket;
import com.family.finance.service.expense.imports.BillCategoryResolver.Line;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.32 FR-1150 · 先确认、后导入:账单里和本期<b>已确认的固定支出</b>同账户、同金额的那一行,
 * 挪进「不建议录入」(默认不勾、写明原因、能勾回来)。
 *
 * <p>不新造机制:{@code SUGGEST_SKIP} 这一档 v1.22 起就是「默认不勾、但能捞回来、逐行给理由」。
 * 一条已确认的固定支出<b>只抵掉一行</b> —— 同一天在同一个账户付了两笔同样的钱是真实存在的,不能一起藏掉。
 * 只读,不碰库里的流水。</p>
 */
@Service
@RequiredArgsConstructor
public class FixedFlowImportDedupe {

    private final FixedFlowMapper mapper;

    public BillCategoryResolver.Draft markConfirmed(long familyId, long periodId, BillCategoryResolver.Draft draft) {
        if (draft == null) return null;
        List<FixedFlowMapper.ConfirmedExpense> pool = new ArrayList<>(mapper.confirmedExpenses(familyId, periodId));
        if (pool.isEmpty()) return draft;
        List<Line> out = new ArrayList<>(draft.lines().size());
        for (Line l : draft.lines()) {
            FixedFlowMapper.ConfirmedExpense hit = null;
            if (l.bucket() == Bucket.SPEND && l.accountId() != null && l.amount() != null) {
                for (var c : pool) {
                    if (l.accountId().equals(c.accountId()) && l.amount().compareTo(c.amount()) == 0) {
                        hit = c;
                        break;
                    }
                }
            }
            if (hit == null) {
                out.add(l);
                continue;
            }
            pool.remove(hit);
            out.add(new Line(l.idx(), l.occurredAt(), l.merchant(), l.amount(), l.categoryId(), l.categoryName(),
                    l.how(), Bucket.SUGGEST_SKIP, l.natureCode(),
                    "已作为固定支出记过 · " + hit.name() + "(真是另一笔就勾上)",
                    l.txNo(), l.payMethod(), l.accountId(), l.accountHow()));
        }
        return new BillCategoryResolver.Draft(draft.channel(), out, draft.total(), draft.parsed(), draft.noTxNo());
    }
}
