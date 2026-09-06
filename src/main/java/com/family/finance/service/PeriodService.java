package com.family.finance.service;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodMemberCompletion;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMemberCompletionMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotTodoMapper;
import com.family.finance.service.recompute.MetricsRecomputeJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PeriodService {

    private final PeriodMapper periodMapper;
    private final MemberMapper memberMapper;
    private final SnapshotTodoMapper snapshotTodoMapper;
    private final PeriodMemberCompletionMapper completionMapper;
    private final com.family.finance.repository.PeriodReopenLogMapper periodReopenLogMapper;
    private final com.family.finance.repository.SnapshotMapper snapshotMapperRef;
    // v1.12 FR-350 · 关账时把账户的分类属性一并定格 / 重开时删掉定格行
    private final com.family.finance.repository.PeriodAccountAttrMapper periodAccountAttrMapper;
    /** v1.19.16 · 重开时要连它一起清 —— 这张表以前没有任何失效入口 */
    private final com.family.finance.repository.ReviewAiCacheMapper reviewAiCacheMapper;
    private final AuditLogService auditLogService;
    private final MetricsRecomputeJob metricsRecomputeJob;

    // v0.3 FR-53b/c · 可选注入 · 失败/未配 LLM 时 null 安全(@Autowired required=false)
    @Autowired(required = false)
    private com.family.finance.service.goal.GoalReportService goalReportService;
    // v0.5 FR-82 · 周期关闭时重算 AUTO 模式 FIRE 目标月支出
    @Autowired(required = false)
    private com.family.finance.service.goal.GoalService goalService;
    // v1.2 · 关账时归档再平衡活动计划
    @Autowired(required = false)
    private com.family.finance.service.review.RebalancePlanService rebalancePlanService;

    public Optional<Period> findCurrentOpen(long familyId) {
        return periodMapper.findCurrentOpen(familyId);
    }

    public Period requireCurrentOpen(long familyId) {
        return findCurrentOpen(familyId)
                .orElseThrow(() -> new IllegalStateException("当前没有 OPEN 周期"));
    }

    public List<Period> findRange(long familyId, LocalDate from, LocalDate to) {
        return periodMapper.findRange(familyId, from, to);
    }

    public List<Period> findLatest(long familyId, int limit) {
        return periodMapper.findLatest(familyId, limit);
    }

    /** v0.5 修 · 周期管理分页 */
    public List<Period> findPaged(long familyId, int limit, int offset) {
        return periodMapper.findPaged(familyId, limit, offset);
    }

    public int countPeriods(long familyId) {
        return periodMapper.countByFamily(familyId);
    }

    @Transactional
    public Period openIfAbsent(Family family, LocalDate startDate) {
        return periodMapper.findByNatural(family.getId(), family.getPeriodType(), startDate)
                .orElseGet(() -> {
                    Period period = Period.builder()
                            .familyId(family.getId())
                            .periodType(family.getPeriodType())
                            .periodStart(startDate)
                            .periodEnd(periodEnd(family.getPeriodType(), startDate))
                            .status(PeriodStatus.OPEN)
                            .build();
                    periodMapper.insert(period);
                    auditLogService.record(family.getId(), null, AuditLogType.PERIOD_OPEN,
                            "period", period.getId(), "自动创建周期 " + startDate);
                    return period;
                });
    }

    @Transactional
    public void close(MemberPrincipal me, long periodId) {
        close(periodId, me.getMemberId(), "关闭周期");
    }

    @Transactional
    public void close(long periodId) {
        close(periodId, null, "全员完成自动关闭周期");
    }

    /**
     * 强制关闭周期(管理员操作,见 PRD §7.9 第六批维护):
     *   - 找出本期所有 PENDING 的账户,upsert period_snapshot.end_balance = 上期末(延续)
     *   - 标记所有 todo DONE
     *   - 为所有未完成填报的成员代签 period_member_completion(由 actor 名义)
     *   - 调用 close 标 status=CLOSED + 异步 metrics 重算
     */
    @Transactional
    public int forceClose(long periodId, long actorMemberId) {
        Period period = periodMapper.findById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("周期不存在: " + periodId));
        if (period.getStatus() != PeriodStatus.OPEN) {
            throw new IllegalStateException("周期已是 CLOSED,无需强制关闭");
        }
        int filledFromPrev = 0;
        for (com.family.finance.domain.snapshot.SnapshotTodo todo : snapshotTodoMapper.findByPeriod(periodId)) {
            if (todo.getStatus() != com.family.finance.domain.snapshot.TodoStatus.PENDING) continue;
            // v0.2 bug 修(2026-05-10): 防御深度 — 若 snapshot 已存在(可能由 cash_flow/transfer 路径
            // 写入而 todo 因历史 bug 未标 DONE),不允许"延续上期末"覆盖真实余额,
            // 仅把 todo 标 DONE 即可。
            boolean snapshotExists = snapshotMapperRef
                    .findByPeriodAndAccount(periodId, todo.getAccountId()).isPresent();
            if (!snapshotExists) {
                java.math.BigDecimal prevBalance = snapshotMapperRef
                        .findLatestBefore(todo.getAccountId(), period.getPeriodStart(), 1)
                        .stream().findFirst()
                        .map(com.family.finance.domain.snapshot.PeriodSnapshot::getEndBalance)
                        .orElse(java.math.BigDecimal.ZERO);
                snapshotMapperRef.upsert(com.family.finance.domain.snapshot.PeriodSnapshot.builder()
                        .periodId(periodId)
                        .accountId(todo.getAccountId())
                        .endBalance(prevBalance)
                        .submittedBy(actorMemberId)
                        .note("强制关账:延续上期末余额 " + prevBalance)
                        .build());
                filledFromPrev++;
            }
            snapshotTodoMapper.markDone(periodId, todo.getAccountId(), actorMemberId);
        }
        // 全员代签 period_member_completion
        for (com.family.finance.domain.member.Member m : memberMapper.findActiveByFamily(period.getFamilyId())) {
            completionMapper.insertIgnore(PeriodMemberCompletion.builder()
                    .periodId(periodId)
                    .memberId(m.getId())
                    .build());
        }
        auditLogService.record(period.getFamilyId(), actorMemberId, AuditLogType.PERIOD_CLOSE,
                "period", periodId, "管理员强制关闭周期(代填 " + filledFromPrev + " 个账户的余额=上期末)");
        close(periodId, actorMemberId, "管理员强制关闭周期 · 代填 " + filledFromPrev + " 行");
        return filledFromPrev;
    }

    @Transactional
    public void markCompletedByMember(long periodId, long memberId) {
        Period period = periodMapper.findById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("周期不存在: " + periodId));
        memberMapper.findById(memberId)
                .filter(member -> member.getFamilyId().equals(period.getFamilyId()))
                .orElseThrow(() -> new IllegalArgumentException("成员不属于该家庭"));
        completionMapper.insertIgnore(PeriodMemberCompletion.builder()
                .periodId(periodId)
                .memberId(memberId)
                .build());
        auditLogService.record(period.getFamilyId(), memberId, AuditLogType.SYSTEM,
                "period", periodId, "成员提交本期完成");
        int activeMembers = memberMapper.countActiveByFamily(period.getFamilyId());
        int completedMembers = completionMapper.countByPeriod(periodId);
        int pendingTodos = snapshotTodoMapper.countPendingByPeriod(periodId);
        if (activeMembers > 0 && completedMembers >= activeMembers && pendingTodos == 0) {
            close(periodId, null, "全员完成并自动关闭周期");
        }
    }

    @Transactional
    public void reopen(MemberPrincipal me, long periodId) {
        reopen(periodId, me.getMemberId(), "手动重新打开周期");
    }

    @Transactional
    public void reopen(long periodId, String reason) {
        reopen(periodId, null, reason);
    }

    private void reopen(long periodId, Long actorMemberId, String reason) {
        Period period = periodMapper.findById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("周期不存在: " + periodId));
        periodMapper.reopen(period.getFamilyId(), periodId);
        completionMapper.deleteByPeriod(periodId);
        // v1.12 FR-350 · 删掉该期的分类属性定格行 → 这期又跟着当前设置走(和「未关账 = 实时」一致),
        // 而「重开后再关账 = 重新定格」变成**结构上必然**的,不需要额外标志位或版本号。
        periodAccountAttrMapper.deleteByPeriod(periodId);
        // v1.19.16 · AI 月度复盘缓存也要一起清。
        //   它按 (family, period, dim) 存,而这里以前只清了填报完成态和定格行 ——
        //   于是「重开 → 改数据 → 重新关账」之后,复盘还是重开前那份结论。
        //   线上用户(issue #17)撞到的就是这个:数字都更新了,只有这段解读没动,
        //   而它旁边写着「关账后结果缓存可回看」,读起来就是本期定论。
        //   放在重开这一刻而不是再次关账:解读在数据被动的那一秒就作废了。
        reviewAiCacheMapper.deleteByPeriod(period.getFamilyId(), periodId);
        String safeReason = reason == null || reason.isBlank() ? "(未填写)" : reason;
        // PRD FR-12 验收:写入 period_reopen_log 专表
        periodReopenLogMapper.insert(periodId, actorMemberId, safeReason);
        auditLogService.record(period.getFamilyId(), actorMemberId, AuditLogType.PERIOD_REOPEN,
                "period", periodId, "重新打开周期: " + safeReason);
    }

    private void close(long periodId, Long actorMemberId, String summary) {
        Period period = periodMapper.findById(periodId)
                .orElseThrow(() -> new IllegalArgumentException("周期不存在: " + periodId));
        periodMapper.close(period.getFamilyId(), periodId);
        // v1.12 FR-350 · 关账 = 封板,分类属性也要一起定格。
        //
        // 位置刻意选在这里:periodMapper.close() 之后、runMetricsAfterCommit() 之前,**同一事务内**。
        //   · 不放 afterCommit / 不包 try-catch:定格失败就不该关账成功。一个「关了账但没定格」的期,
        //     后续读会回落当前属性 —— 表面正常,实际漏保护,且不报警。
        //   · 与下面三段非阻塞钩子(FIRE 重算 / 再平衡归档 / AI 月报)的区别:那三段是**衍生产物**,
        //     失败可重跑;定格是**这一刻才存在的事实**,过了就没了,不能降级。
        periodAccountAttrMapper.freezeByPeriod(periodId);
        auditLogService.record(period.getFamilyId(), actorMemberId, AuditLogType.PERIOD_CLOSE,
                "period", periodId, summary);
        runMetricsAfterCommit(periodId);

        // v0.5 FR-82 · 周期关闭 = 月结落定 → 重算 AUTO 模式 FIRE 目标月支出(失败不阻塞)
        try {
            if (goalService != null) {
                goalService.recomputeAutoExpenseGoals(period.getFamilyId());
            }
        } catch (Exception e) {
            log.warn("post-close FIRE expense recompute failed (non-blocking): {}", e.toString());
        }

        // v1.2 · 关账 = 再平衡活动计划归档(可回看 · 失败不阻塞)
        try {
            if (rebalancePlanService != null) {
                rebalancePlanService.archiveOnClose(period.getFamilyId());
            }
        } catch (Exception e) {
            log.warn("post-close rebalance plan archive failed (non-blocking): {}", e.toString());
        }

        // v0.3 FR-53b/c · 异步触发 AI 月报 + 偏离预警 · 失败不阻塞 close 主流程
        try {
            if (goalReportService != null) {
                goalReportService.generateMonthlyReportsAsync(period.getFamilyId(), periodId);
                goalReportService.checkAndAlertAsync(period.getFamilyId(), periodId);
            }
        } catch (Exception e) {
            // 防御性兜底:任何异常不应影响周期关闭主流程
            log.warn("post-close AI hooks failed (non-blocking): {}", e.toString());
        }
    }

    private void runMetricsAfterCommit(long periodId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    metricsRecomputeJob.run(periodId);
                }
            });
        } else {
            metricsRecomputeJob.run(periodId);
        }
    }

    public boolean isPeriodStartDate(PeriodType type, LocalDate date) {
        return switch (type) {
            case MONTHLY -> date.getDayOfMonth() == 1;
            case WEEKLY -> date.getDayOfWeek() == DayOfWeek.MONDAY;
        };
    }

    public LocalDate currentPeriodStart(PeriodType type, LocalDate today) {
        return switch (type) {
            case MONTHLY -> today.withDayOfMonth(1);
            case WEEKLY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        };
    }

    /** 给定一个 period_start,返回下一周期的 period_start。 */
    public LocalDate nextPeriodStart(PeriodType type, LocalDate currentStart) {
        return switch (type) {
            case MONTHLY -> currentStart.plusMonths(1).withDayOfMonth(1);
            case WEEKLY -> currentStart.plusWeeks(1);
        };
    }

    private LocalDate periodEnd(PeriodType type, LocalDate startDate) {
        return switch (type) {
            case MONTHLY -> startDate.plusMonths(1).minusDays(1);
            case WEEKLY -> startDate.plusDays(6);
        };
    }
}
