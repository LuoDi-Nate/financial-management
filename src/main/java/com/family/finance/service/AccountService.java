package com.family.finance.service;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.member.MemberDirectory;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class AccountService {

    private final AccountMapper accountMapper;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher; // v1.1.1 lens 缓存失效事件
    /** v1.15 FR-382 · 名字映射走名录(含已归档)—— 归档一个人,不该让历史数据里的他变成无名氏 */
    private final MemberDirectory memberDirectory;
    private final PeriodMapper periodMapper;
    private final SnapshotMapper snapshotMapper;
    private final AuditLogService auditLogService;
    private final ProductCategoryService productCategoryService;
    /** v1.28.1 · 账户页顶上按类型的合计要先换成本位币再加(issue #25:加密货币按 USD 数加、却标 ¥) */
    private final com.family.finance.repository.FxMapper fxMapper;
    private final com.family.finance.repository.FamilyMapper familyMapper;

    public List<Account> findActiveByFamily(long familyId) {
        return accountMapper.findActiveByFamily(familyId);
    }

    public List<AccountRow> listRows(long familyId, boolean includeArchived) {
        List<Account> accounts = includeArchived
                ? accountMapper.findAllByFamily(familyId)
                : accountMapper.findActiveByFamily(familyId);
        Map<Long, Member> members = memberDirectory.listAll(familyId).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        Map<Long, Account> accountsById = accounts.stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Optional<Period> period = periodMapper.findBalancePeriod(familyId)
                .or(() -> periodMapper.findLatest(familyId, 1).stream().findFirst());
        Map<Long, PeriodSnapshot> snapshots = period
                .map(p -> snapshotMapper.findByPeriod(familyId, p.getId()).stream()
                        .collect(Collectors.toMap(PeriodSnapshot::getAccountId, Function.identity())))
                .orElseGet(Map::of);

        // v0.2 · 一次加载所有类目,模板免重复查 (FR-40d)
        Map<String, com.family.finance.domain.category.ProductCategory> categoriesByCode =
                productCategoryService.listAll().stream()
                        .collect(Collectors.toMap(
                                com.family.finance.domain.category.ProductCategory::getCode,
                                Function.identity()));

        List<AccountRow> rows = new ArrayList<>();
        for (Account account : accounts) {
            PeriodSnapshot snapshot = snapshots.get(account.getId());
            BigDecimal balance = snapshot == null ? null : snapshot.getEndBalance();
            Member owner = account.getPrimaryOwnerMemberId() == null ? null : members.get(account.getPrimaryOwnerMemberId());
            Account source = account.getDefaultPaymentSourceAccountId() == null
                    ? null
                    : accountsById.get(account.getDefaultPaymentSourceAccountId());
            com.family.finance.domain.category.ProductCategory cat =
                    account.getProductCategoryCode() == null
                            ? null
                            : categoriesByCode.get(account.getProductCategoryCode());
            rows.add(new AccountRow(
                    account,
                    owner == null ? "共同" : owner.getDisplayName(),
                    source == null ? "—" : source.getDisplayName(),
                    snapshot,
                    balance,
                    MoneyFormat.formatForAccount(account.getType(), account.getCurrency(), balance),
                    account.isArchived(),
                    cat
            ));
        }
        return rows;
    }

    public List<AccountTypeSummary> summarize(long familyId) {
        List<AccountRow> rows = listRows(familyId, false);
        // v1.28.1 · issue #25 · 各账户余额是本币(加密货币多为 USD、港股 HKD),原来直接相加再标 ¥ ——
        //   换成本位币再加,用余额所在那一期的汇率(与净资产同一套:本位币 = 原币 ÷ rate)。
        String baseCcy = familyMapper.findById(familyId).map(f -> f.getBaseCurrency()).orElse("CNY");
        Long balancePeriod = rows.stream().map(AccountRow::currentSnapshot).filter(java.util.Objects::nonNull)
                .map(PeriodSnapshot::getPeriodId).filter(java.util.Objects::nonNull)
                .max(Long::compare).orElse(null);
        Map<String, BigDecimal> rateCache = new java.util.HashMap<>();
        Map<AccountType, Integer> counts = new EnumMap<>(AccountType.class);
        Map<AccountType, BigDecimal> amounts = new EnumMap<>(AccountType.class);
        for (AccountType type : AccountType.values()) {
            counts.put(type, 0);
            amounts.put(type, BigDecimal.ZERO);
        }
        for (AccountRow row : rows) {
            AccountType type = row.account().getType();
            counts.compute(type, (k, v) -> v == null ? 1 : v + 1);
            if (row.currentBalance() != null) {
                BigDecimal inBase = toBase(familyId, baseCcy, row.account().getCurrency(), balancePeriod,
                        row.currentBalance(), rateCache);
                amounts.compute(type, (k, v) -> (v == null ? BigDecimal.ZERO : v).add(inBase));
            }
        }
        List<AccountTypeSummary> summary = new ArrayList<>();
        for (AccountType type : AccountType.values()) {
            summary.add(new AccountTypeSummary(type, counts.get(type), amounts.get(type),
                    MoneyFormat.format(baseCcy, amounts.get(type) == null ? BigDecimal.ZERO : amounts.get(type))));
        }
        summary.sort(Comparator.comparingInt(s -> switch (s.type()) {
            case CASH -> 1;
            case STOCK -> 2;
            case WEALTH -> 3;
            case FUND -> 3;        // v1.30 · 紧跟理财(同一组「交给产品打理」的投资账户)
            case CRYPTO -> 4;
            case METAL -> 5;
            case PROPERTY -> 6;
            case LOAN -> 7;
            case OTHER -> 8;
            case INSURANCE -> 9;
        }));
        return summary;
    }

    /**
     * 原币 → 本位币。拿不到汇率时原样返回并记一行告警(与净资产那边「拉不到就按原币加」的兜底一致,
     * 不因为一个币种缺汇率让整张卡片消失)。
     */
    private BigDecimal toBase(long familyId, String baseCcy, String ccy, Long periodId, BigDecimal amount,
                              Map<String, BigDecimal> cache) {
        if (ccy == null || ccy.equalsIgnoreCase(baseCcy) || amount == null) return amount;
        BigDecimal rate = cache.computeIfAbsent(ccy.toUpperCase(), q -> {
            var r = periodId == null ? java.util.Optional.<com.family.finance.domain.fx.FxRate>empty()
                    : fxMapper.findOne(familyId, baseCcy, q, periodId);
            if (r.isEmpty()) r = fxMapper.findLatest(familyId, baseCcy, q);
            return r.map(com.family.finance.domain.fx.FxRate::getRate).orElse(null);
        });
        if (rate == null || rate.signum() == 0) {
            log.warn("账户页合计:拉不到 {} → {} 的汇率,按原币相加", baseCcy, ccy);
            return amount;
        }
        return amount.divide(rate, 2, java.math.RoundingMode.HALF_EVEN);
    }

    public Account require(long familyId, long accountId) {
        Account account = accountMapper.findById(familyId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("账户不存在: " + accountId));
        if (account.getFamilyId() != familyId) {
            throw new IllegalArgumentException("账户不属于当前家庭");
        }
        return account;
    }

    @Transactional
    public Account create(MemberPrincipal me, Account account) {
        account.setFamilyId(me.getFamilyId());
        normalizeLoanFields(account);
        // v0.2 · 类目缺省时按 account.type 给 default(FR-40d)
        if (account.getProductCategoryCode() == null || account.getProductCategoryCode().isBlank()) {
            account.setProductCategoryCode(productCategoryService.defaultCodeFor(account.getType()));
        }
        if (account.getDisplayOrder() == null) {
            account.setDisplayOrder(nextDisplayOrder(me.getFamilyId()));
        }
        accountMapper.insert(account);
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(me.getFamilyId())); // v1.1.1 透视缓存后台换新
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.ACCOUNT_CREATE,
                "account", account.getId(), "创建账户 " + account.getDisplayName());
        return account;
    }

    @Transactional
    public void update(MemberPrincipal me, long accountId, Account update) {
        Account existing = require(me.getFamilyId(), accountId);
        update.setId(existing.getId());
        update.setFamilyId(existing.getFamilyId());
        normalizeLoanFields(update);
        // v0.2 · 编辑时若用户清空类目,沿用旧值(不允许 NULL)
        if (update.getProductCategoryCode() == null || update.getProductCategoryCode().isBlank()) {
            update.setProductCategoryCode(existing.getProductCategoryCode());
        }
        accountMapper.update(update);
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(me.getFamilyId())); // v1.1.1 透视缓存后台换新
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.ACCOUNT_UPDATE,
                "account", accountId, "更新账户 " + update.getDisplayName());
    }

    /**
     * v1.27 FR-800 · 勾 / 取消「不参与配置分析」。不定格(FR-803):改完所有分析立即按新值看。
     * 进审计:家里人能在审计日志里看到是谁、什么时候把哪个账户拿出了配置分析。
     */
    @Transactional
    public void setAnalysisExcluded(MemberPrincipal me, long accountId, boolean excluded) {
        Account existing = require(me.getFamilyId(), accountId);
        accountMapper.updateAnalysisExcluded(me.getFamilyId(), accountId, excluded);
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.ACCOUNT_UPDATE,
                "account", accountId, (excluded ? "标成不参与配置分析 " : "取消不参与配置分析 ")
                        + existing.getDisplayName());
    }

    @Transactional
    public void archive(MemberPrincipal me, long accountId) {
        accountMapper.archive(me.getFamilyId(), accountId);
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(me.getFamilyId())); // v1.1.1 透视缓存后台换新
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.ACCOUNT_ARCHIVE,
                "account", accountId, "归档账户");
    }

    @Transactional
    public void restore(MemberPrincipal me, long accountId) {
        accountMapper.restore(me.getFamilyId(), accountId);
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(me.getFamilyId())); // v1.1.1 透视缓存后台换新
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.ACCOUNT_RESTORE,
                "account", accountId, "恢复账户");
    }

    private int nextDisplayOrder(long familyId) {
        return accountMapper.findAllByFamily(familyId).stream()
                .map(Account::getDisplayOrder)
                .filter(v -> v != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private void normalizeLoanFields(Account account) {
        if (account.getType() != AccountType.LOAN) {
            account.setDefaultPaymentSourceAccountId(null);
        }
    }

}
