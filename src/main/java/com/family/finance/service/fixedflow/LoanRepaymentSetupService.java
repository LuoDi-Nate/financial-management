package com.family.finance.service.fixedflow;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.fixedflow.FixedFlow;
import com.family.finance.domain.ledger.LedgerSource;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FixedFlowMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.MoneyFormat;
import com.family.finance.service.member.MemberDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LoanRepaymentSetupService {
    private final AccountMapper accounts;
    private final FixedFlowMapper flows;
    private final PeriodMapper periods;
    private final SnapshotMapper snapshots;
    private final MemberDirectory members;
    private final AuditLogService audit;

    public enum State { SET, UNSET, MANUAL, SETTLED, UNKNOWN }
    public record Row(Account account, String owner, BigDecimal balance, String balanceLabel,
                      State state, FixedFlow flow, LocalDate balancePeriod) {
        public boolean needsSetup() { return state == State.UNSET; }
        public String stateLabel() { return switch (state) {
            case SET -> "已设固定还款"; case UNSET -> "还没设固定还款";
            case MANUAL -> "不设,每月自己填"; case SETTLED -> "已还清"; case UNKNOWN -> "先补余额";
        }; }
    }

    public List<Row> list(long familyId) {
        Period current = periods.findBalancePeriod(familyId).orElseGet(()->
                periods.findRecentAsOf(familyId, LocalDate.now(), 1).stream().findFirst().orElse(null));
        Map<Long,PeriodSnapshot> now = current == null ? Map.of() : byAccount(snapshots.findByPeriod(familyId,current.getId()));
        Period previous = current == null ? null : periods.previousOf(familyId,current.getId()).orElse(null);
        Map<Long,PeriodSnapshot> before = previous == null ? Map.of() : byAccount(snapshots.findByPeriod(familyId,previous.getId()));
        Map<Long,FixedFlow> active = flows.findActiveByFamily(familyId).stream()
                .filter(f->FixedFlow.REPAY.equals(f.getKind()) && f.getTargetAccountId()!=null)
                .collect(Collectors.toMap(FixedFlow::getTargetAccountId,Function.identity(),(a,b)->a));
        Map<Long,String> names=members.nameMap(familyId);
        return accounts.findActiveByFamily(familyId).stream().filter(a->a.getType()==AccountType.LOAN).map(a->{
            var row=now.getOrDefault(a.getId(),before.get(a.getId()));
            BigDecimal balance=row==null?null:row.getEndBalance();
            State state=state(a,balance,active.containsKey(a.getId()));
            var last=before.get(a.getId());
            BigDecimal shown=last==null?balance:last.getEndBalance();
            return new Row(a,a.getPrimaryOwnerMemberId()==null?"共同":names.getOrDefault(a.getPrimaryOwnerMemberId(),"共同"),shown,
                    shown==null?"余额待补齐":MoneyFormat.format(a.getCurrency(),shown.abs()),state,active.get(a.getId()),
                    previous==null?(current==null?null:current.getPeriodStart()):previous.getPeriodStart());
        }).toList();
    }

    public Row forAccount(long familyId,long accountId) { return list(familyId).stream().filter(r->r.account().getId()==accountId).findFirst().orElse(null); }

    static State state(Account account,BigDecimal balance,boolean active) {
        if (active) return State.SET;
        if (account.isLoanManualRepayment()) return State.MANUAL;
        if (balance==null) return State.UNKNOWN;
        return balance.signum()==0?State.SETTLED:State.UNSET;
    }

    public static boolean submitted(PeriodSnapshot snapshot) {
        return snapshot!=null && LedgerSource.parse(snapshot.getSourceTag())!=LedgerSource.CARRIED_FORWARD
                && (snapshot.getSubmittedBy()!=null || snapshot.getSubmittedAt()!=null);
    }

    @Transactional
    public void setManual(long familyId,long memberId,long accountId,boolean manual) {
        Account account=accounts.findById(familyId,accountId).orElseThrow(()->new IllegalArgumentException("账户不存在"));
        if(account.getType()!=AccountType.LOAN || account.isArchived()) throw new IllegalArgumentException("只能修改在用贷款的还款设置");
        if(manual && flows.findActiveByFamily(familyId).stream().anyMatch(f->FixedFlow.REPAY.equals(f.getKind()) && java.util.Objects.equals(f.getTargetAccountId(),accountId)))
            throw new IllegalArgumentException("这笔贷款已有固定还款,停用后才能选择每月自己填");
        if(accounts.updateLoanManualRepayment(familyId,accountId,manual)!=1) throw new IllegalStateException("设置未保存");
        audit.record(familyId,memberId,AuditLogType.FAMILY_UPDATE,"account",accountId,
                manual?"不设固定还款,每月自己填余额":"恢复固定还款设置提醒");
    }

    private static Map<Long,PeriodSnapshot> byAccount(List<PeriodSnapshot> rows) {
        return rows.stream().collect(Collectors.toMap(PeriodSnapshot::getAccountId,Function.identity(),(a,b)->a));
    }
}
