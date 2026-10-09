package com.family.finance.service.ledger;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.ledger.PrincipalAdjustment;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.PrincipalAdjustmentMapper;
import com.family.finance.service.lens.LensStaleEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * v1.30 · 补录本金(PRD FR-973 ~ 975 · tech-design/v1.30.md 选型十二)。
 *
 * <p>「以前就有、这期才补录进来」的钱,口径同开账基线:算本金,不算收入、不算收益。
 * 只给<b>已有历史</b>的资产账户用 —— 新账户第一期的余额已经整笔算开账基线;负债账户补录欠款建新账户即可。</p>
 */
@Service
@RequiredArgsConstructor
public class PrincipalAdjustmentService {

    private final PrincipalAdjustmentMapper mapper;
    private final AccountMapper accountMapper;
    private final PeriodMapper periodMapper;
    private final ApplicationEventPublisher events;

    /** 这个账户在这一期能不能补录本金;不能时 {@link #reason} 是给用户看的一句话 */
    public record Eligibility(Period period, boolean ok, String reason) {}

    public Eligibility check(long familyId, long accountId, Long periodId) {
        Account acc = accountMapper.findById(familyId, accountId).orElse(null);
        if (acc == null || acc.getFamilyId() == null || acc.getFamilyId() != familyId) {
            return new Eligibility(null, false, "账户不存在");
        }
        if (acc.getArchivedAt() != null) return new Eligibility(null, false, "账户已归档");
        if (acc.getType() != null && acc.getType().isLiability()) {
            return new Eligibility(null, false, "负债账户不用补录本金 —— 新纳入的欠款建一个新账户即可");
        }
        Period p = periodId == null ? null : periodMapper.findById(familyId, periodId).orElse(null);
        if (p == null) return new Eligibility(null, false, "没有这一期");
        if (p.getStatus() != PeriodStatus.OPEN) return new Eligibility(p, false, "这一期已经关账了");
        if (mapper.findPreviousEndBalance(familyId, accountId, p.getPeriodStart()) == null) {
            return new Eligibility(p, false, "这是这个账户的第一期 —— 第一期的余额本来就整笔算本金,不用补录");
        }
        return new Eligibility(p, true, null);
    }

    /** 持仓变动落在哪一期:估值写回的那一期(余额期) */
    public Eligibility checkBalancePeriod(long familyId, long accountId) {
        Long pid = periodMapper.findBalancePeriod(familyId).map(Period::getId).orElse(null);
        return check(familyId, accountId, pid);
    }

    /** 账户详情页的「补录本金」表单:能选哪几期(还开着、且不是这个账户的第一期) */
    public List<Period> recordablePeriods(long familyId, long accountId) {
        return periodMapper.findRecordableOpen(familyId).stream()
                .filter(p -> check(familyId, accountId, p.getId()).ok())
                .toList();
    }

    public PrincipalAdjustment record(long familyId, Long memberId, long accountId, Long periodId,
                                      BigDecimal amount, Long holdingId, String note) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("补录金额必须大于 0");
        Eligibility e = check(familyId, accountId, periodId);
        if (!e.ok()) throw new IllegalArgumentException(e.reason());
        PrincipalAdjustment pa = PrincipalAdjustment.builder()
                .accountId(accountId).periodId(periodId)
                .amount(amount.setScale(2, RoundingMode.HALF_EVEN))
                .holdingId(holdingId)
                .note(note == null || note.isBlank() ? null : note.trim().length() > 255 ? note.trim().substring(0, 255) : note.trim())
                .sourceTag("MANUAL")
                .memberId(memberId)
                .build();
        if (mapper.insert(familyId, pa) != 1) throw new IllegalStateException("补录本金没有记上(账户或这一期不属于这个家,或已关账)");
        events.publishEvent(new LensStaleEvent(familyId));
        return pa;
    }

    public void delete(long familyId, long accountId, long id) {
        if (mapper.softDelete(familyId, accountId, id) != 1) {
            throw new IllegalArgumentException("删不了:这一笔不存在,或那一期已经关账");
        }
        events.publishEvent(new LensStaleEvent(familyId));
    }
}
