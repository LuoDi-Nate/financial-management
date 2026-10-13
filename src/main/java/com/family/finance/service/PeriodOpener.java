package com.family.finance.service;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.domain.snapshot.SnapshotTodo;
import com.family.finance.domain.snapshot.TodoStatus;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.repository.SnapshotTodoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PeriodOpener {


    private final FamilyService familyService;
    private final PeriodService periodService;
    private final AccountMapper accountMapper;
    private final MemberMapper memberMapper;
    private final PeriodMapper periodMapper;
    private final SnapshotMapper snapshotMapper;
    private final SnapshotTodoMapper snapshotTodoMapper;

    /**
     * 每日开账检查 · **只开新期,不关旧期**(v1.23 起)。
     *
     * <p>v1.22 及以前这里会先 {@code closePriorOpenPeriods} 再建新期 —— 也就是说
     * 「关账」挂在「开账」这个事件上。v1.23 引入关账宽限后这行不通了:
     * 宽限期内「该关的日子」和「该开的日子」不再是同一天,继续挂在一起
     * 就会**漏关**(宽限到期那天没有新期滚动来触发,账期永远悬在 OPEN)。
     * 关账搬到 {@link #closeIfGraceExpired()}。</p>
     */
    @Scheduled(cron = "0 30 0 * * *")
    @Transactional
    public void openIfDue() {
        LocalDate today = LocalDate.now();
        for (Family family : familyService.findAll()) {
            if (periodService.isPeriodStartDate(family.getPeriodType(), today)) {
                createPeriodAndTodos(family, today);
            }
        }
    }

    /**
     * v1.23 FR-614 / FR-615 · 每日关账检查 · **只关,不开**。
     *
     * <p>比 {@link #openIfDue()} 晚 5 分钟跑:**先开新期再判关账**,
     * 这样「开新期时上期的宽限还在」这个顺序是确定的,不依赖两个任务的调度巧合。</p>
     *
     * <p>逐个家庭、逐个 OPEN 期判:</p>
     * <ul>
     *   <li><b>自动模式</b>:{@code today > period_end + 宽限天数} → 关。
     *       T+0 时等价于 v1.22 的行为(新期第一天就成立)。</li>
     *   <li><b>手动模式</b>:平时不关;但**拖过一期**(下下期已开)就强制关 ——
     *       FR-612 的兜底,理由见 {@code PeriodService.mustForceCloseOverdue}。</li>
     * </ul>
     *
     * <p>打日志是刻意的:关账会让一批数字同时跳(收益类锚点前移、月均支出纳入本期、
     * AI 月报生成、属性与分组定格),出问题时必须能从日志看出它判了什么、为什么。</p>
     */
    @Scheduled(cron = "0 35 0 * * *")
    @Transactional
    public void closeIfGraceExpired() {
        LocalDate today = LocalDate.now();
        for (Family family : familyService.findAll()) {
            Long systemMemberId = firstActiveMemberId(family.getId());
            if (systemMemberId == null) continue;   // 无活跃成员 → 无法代签,跳过
            for (Period open : periodMapper.findRecordableOpen(family.getId())) {
                if (open.getPeriodEnd() == null || !open.getPeriodEnd().isBefore(today)) {
                    continue;   // 还没自然结束 = 正常的进行期
                }
                boolean auto = periodService.shouldAutoClose(family, open, today);
                boolean overdue = !family.autoCloseOrDefault()
                        && periodService.mustForceCloseOverdue(family.getId(), open);
                if (!auto && !overdue) {
                    log.info("[grace-close] family={} period={} deadline={} closed=false · 宽限内(剩 {} 天)",
                            family.getId(), open.getId(), periodService.graceDeadline(family, open),
                            periodService.graceDaysLeft(family, open, today));
                    continue;
                }
                try {
                    int filled = periodService.forceClose(family.getId(), open.getId(), systemMemberId);
                    log.info("[grace-close] family={} period={} deadline={} closed=true reason={} 代填={}",
                            family.getId(), open.getId(), periodService.graceDeadline(family, open),
                            overdue ? "手动模式拖过一期·强制关" : "宽限到期", filled);
                } catch (IllegalStateException alreadyClosed) {
                    // 并发 / 重复触发下已非 OPEN,跳过
                }
            }
        }
    }

    /**
     * v0.11.2 · 开新期时把「早于新期起始日」仍 OPEN 的旧期 force-close。
     *
     * <p>v1.23 起**只给 {@link #openNextNow} 用**,且只在 T+0(无宽限)时调 ——
     * 有宽限的家庭手动开下一期时,上期本来就该继续开着。
     * 自动路径不再用它(见 {@link #closeIfGraceExpired()})。</p>
     */
    private void closePriorOpenPeriods(Family family, LocalDate newStart) {
        Long systemMemberId = firstActiveMemberId(family.getId());
        if (systemMemberId == null) return;
        for (Period prior : periodMapper.findOpenBefore(family.getId(), newStart)) {
            try {
                periodService.forceClose(family.getId(), prior.getId(), systemMemberId);
            } catch (IllegalStateException alreadyClosed) {
                // 并发/重复触发下已非 OPEN,跳过
            }
        }
    }

    private Long firstActiveMemberId(long familyId) {
        return memberMapper.findActiveByFamily(familyId).stream()
                .findFirst().map(Member::getId).orElse(null);
    }

    /**
     * 测试 / 管理员手动触发:基于"最新已有周期"的下一个 period_start 立即开下一周期。
     * - 不存在任何周期 → 用今天的 currentPeriodStart
     * - 已有周期 → 取最大 period_start 计算 next
     * 同时生成 snapshot_todo + LOAN 预填(走 createPeriodAndTodos)。
     * 见 PRD §7.9 第五批维护。
     */
    @Transactional
    public Period openNextNow(long familyId) {
        Family family = familyService.require(familyId);
        LocalDate seed = periodService.findLatest(familyId, 1).stream()
                .findFirst()
                .map(Period::getPeriodStart)
                .map(start -> periodService.nextPeriodStart(family.getPeriodType(), start))
                .orElseGet(() -> periodService.currentPeriodStart(family.getPeriodType(), LocalDate.now()));
        // v0.11.2 修 bug1:开下一期前,先关早于它仍 OPEN 的旧期(与自动滚动同口径)
        // v1.23:**只在无宽限(T+0)时关**。配了宽限的家庭手动开下一期时,
        //   上期本来就该继续开着 —— 否则「我提前把下月开出来」这个动作会顺手把
        //   还在宽限里的上月关掉,而用户完全没想关它。有宽限时交给 closeIfGraceExpired 按判据处理。
        if (family.closeDelayDaysOrZero() == 0 && family.autoCloseOrDefault()) {
            closePriorOpenPeriods(family, seed);
        }
        Period period = periodService.openIfAbsent(family, seed);
        // 复用既有 todo / LOAN 预填逻辑(已 idempotent)
        createPeriodAndTodos(family, seed);
        return period;
    }

    @Transactional
    public void createPeriodAndTodos(Family family, LocalDate periodStart) {
        Period period = periodService.openIfAbsent(family, periodStart);
        List<Account> accounts = accountMapper.findActiveByFamily(family.getId());
        Long systemMemberId = memberMapper.findActiveByFamily(family.getId()).stream()
                .findFirst()
                .map(Member::getId)
                .orElse(null);

        for (Account account : accounts) {
            if (snapshotTodoMapper.findByPeriodAndAccount(family.getId(), period.getId(), account.getId()).isPresent()) {
                continue;
            }
            SnapshotTodo todo = SnapshotTodo.builder()
                    .periodId(period.getId())
                    .accountId(account.getId())
                    .assignedMemberId(account.getPrimaryOwnerMemberId())
                    .status(TodoStatus.PENDING)
                    .build();

            // 计算"延续值":LOAN 走 prefill(prev + Δ_prev),其它账户 = 上期末
            BigDecimal prefillBalance = computePrefillBalance(period, account, todo, systemMemberId);
            todo.setPrefilledBalance(prefillBalance);
            snapshotTodoMapper.insertOwned(family.getId(), todo);

            // 同时写入 period_snapshot,使每个账户开账即"已平衡 ✓"(用户后续只需调整变化的账户)
            // upsert + 仅当目标 snapshot 不存在时写入(idempotent)
            boolean snapshotExists = snapshotMapper
                    .findByPeriodAndAccount(family.getId(), period.getId(), account.getId()).isPresent();
            if (prefillBalance != null && !snapshotExists) {
                snapshotMapper.upsertOwned(family.getId(), PeriodSnapshot.builder()
                        .periodId(period.getId())
                        .accountId(account.getId())
                        .endBalance(prefillBalance)
                        .submittedBy(systemMemberId)
                        .note("开账自动延续上期末余额 " + prefillBalance)
                        // v1.18 · 系统代填,没有人确认过这个数
                        .sourceTag(com.family.finance.domain.ledger.LedgerSource.CARRIED_FORWARD.name())
                        .build());
                snapshotExists = true;
            }

            // v1.16 FR-390(issue #15)· 这一行已经有本期数字了,todo 就该是"已填"。
            //   在此之前:填报页看「有没有数字」判 ✓、tab 徽标和自动关账看 status='PENDING' 数「未填」,
            //   同一个方法写出来的两份状态互相打架 —— 页面显示全填好了,徽标还挂着 ·1(issue #15)。
            //   判定用"写完之后有没有快照"而不是"有没有延续值":正常路径两者等价,
            //   但幂等重跑 / 快照由别的路径先落库时,前者才是对的。
            //   无历史(首期 / 新建账户的第一期)→ 没有快照 → 保持 PENDING,那时候确实该催。
            if (snapshotExists) {
                snapshotTodoMapper.markCarriedForward(family.getId(), period.getId(), account.getId());
            }
        }
    }

    /**
     * 返回当前账户的预填余额 = 上月末值(prev)。所有账户一致 carry-forward,无历史快照返回 null(首期)。
     *
     * <p>v0.17.x · 贷款不再<b>静默</b>外推 prev+Δ(旧行为:开账即把趋势预测写进 snapshot + 起草还款转账,
     * 用户不知情)。趋势预测改由填报页<b>贷款行内提示条</b>让用户显式「接受 / 保持上月」
     * (见 {@link EntryService#acceptLoanPrediction} + {@link #predictLoanBalance})。
     * 兼容:{@code createPeriodAndTodos} 幂等(已有 todo 则跳过),只影响将来新开账期,老账期不回改。</p>
     */
    private BigDecimal computePrefillBalance(Period period, Account account, SnapshotTodo todo, Long systemMemberId) {
        return snapshotMapper.findLatestBefore(period.getFamilyId(), account.getId(), period.getPeriodStart(), 1)
                .stream().findFirst()
                .map(PeriodSnapshot::getEndBalance)
                .orElse(null);
    }

    /**
     * v0.11.2 · LOAN 预填余额:趋势外推 prev + (prev − prevPrev),但**夹到 ≤ 0**(贷款=欠款,不为正)。
     *   prevPrev 为 null(仅一期历史)→ 直接沿用 prev。修 bug2:-72000 → 0 后外推成 +72000 的错。
     *   例:房贷 -990000←-1000000 → 外推 -980000(不夹);还平 0←-72000 → 外推 +72000 → 夹到 0。
     *
     * <p>v1.32 · 改成 public:固定收支的「还款 · 按上两月差值」直接调它({@code FixedFlowCalc.loanDiff}),
     * 不另写一份 —— PRD v1.32 FR-1108 要求与点「接受」逐分相同,同一个函数是最直接的保证。</p>
     */
    public static BigDecimal predictLoanBalance(BigDecimal prev, BigDecimal prevPrev) {
        BigDecimal predicted = (prevPrev == null) ? prev : prev.add(prev.subtract(prevPrev));
        return predicted.signum() > 0 ? BigDecimal.ZERO : predicted;
    }
}
