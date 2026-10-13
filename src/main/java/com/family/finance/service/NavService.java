package com.family.finance.service;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.SnapshotTodoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NavService {

    private final FamilyService familyService;
    private final PeriodService periodService;
    private final SnapshotTodoMapper snapshotTodoMapper;
    private final com.family.finance.service.fixedflow.FixedFlowSummaryService fixedFlowSummaryService;

    public NavState load(MemberPrincipal me) {
        Family family = familyService.require(me.getFamilyId());
        Period period = periodService.findBalancePeriod(me.getFamilyId()).orElse(null);
        int pending = period == null ? 0 : snapshotTodoMapper.countPendingByPeriod(me.getFamilyId(), period.getId());
        // v1.32 FR-1140 · 徽标 = 没填的账户 + 到了日子还没确认的固定收支(当天起算)
        pending += fixedFlowSummaryService.pendingCount(me.getFamilyId());
        String label = period == null
                ? "未开期"
                : "%d · %02d · %s".formatted(
                        period.getPeriodStart().getYear(),
                        period.getPeriodStart().getMonthValue(),
                        period.getStatus());
        return new NavState(family, period, pending, label);
    }
}
