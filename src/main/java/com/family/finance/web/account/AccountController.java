package com.family.finance.web.account;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountTemplate;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.account.InsuranceSubType;
import com.family.finance.domain.insurance.InsurancePolicy;
import com.family.finance.repository.InsurancePolicyMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.AccountDetailService;
import com.family.finance.service.AccountService;
import com.family.finance.service.AccountTemplateService;
import com.family.finance.service.NavService;
import com.family.finance.service.ProductCategoryService;
import com.family.finance.service.export.LedgerExporter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    /** v1.20 · 账户所属分组(展示用) */
    private final com.family.finance.service.group.AccountGroupService accountGroupService;
    private final AccountTemplateService accountTemplateService;
    /** 仅活跃:新建账户向导的主理人下拉 —— 新账户不该挂到已归档的人身上 */
    private final MemberMapper memberMapper;
    /** v1.15 FR-382 · 编辑表单候选(活跃 ∪ 当前主理人) */
    private final com.family.finance.service.member.MemberDirectory memberDirectory;
    private final NavService navService;
    private final ProductCategoryService productCategoryService;
    private final LedgerExporter ledgerExporter;
    private final AccountDetailService accountDetailService;
    private final com.family.finance.repository.BrokerLinkMapper brokerLinkMapper; // v0.15.x 券商托管徽章
    private final InsurancePolicyMapper insurancePolicyMapper; // v0.17 保单登记旁表

    @GetMapping
    public String index(@AuthenticationPrincipal MemberPrincipal me,
                        @RequestParam(value = "archived", defaultValue = "false") boolean includeArchived,
                        @RequestParam(value = "type", required = false) String typeFilter,
                        Model model) {
        addModel(me, model, includeArchived, false);
        // v1.20 · 「这个账户属于哪个分组」—— 从账户看分组的那一侧。
        //   第一版只做了分组管理页,账户这边一个字都没提,用户只有恰好翻到管理页才知道有这能力。
        model.addAttribute("accountGroupName", accountGroupService.occupiedBy(me.getFamilyId(), null));
        // 用户视角的"按类型筛选"(CASH / STOCK / WEALTH / PROPERTY / LOAN / OTHER / ALL)
        com.family.finance.domain.account.AccountType normalized = null;
        if (typeFilter != null && !typeFilter.isBlank() && !"ALL".equalsIgnoreCase(typeFilter)) {
            try { normalized = com.family.finance.domain.account.AccountType.valueOf(typeFilter.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        @SuppressWarnings("unchecked")
        java.util.List<com.family.finance.service.AccountRow> rows =
                (java.util.List<com.family.finance.service.AccountRow>) model.getAttribute("rows");
        if (normalized != null && rows != null) {
            final var typed = normalized;
            model.addAttribute("rows", rows.stream()
                    .filter(r -> r.account().getType() == typed)
                    .toList());
        }
        model.addAttribute("typeFilter", normalized == null ? "ALL" : normalized.name());
        return "accounts/index";
    }

    @GetMapping("/new")
    public String newWizard(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        addModel(me, model, false, true);
        return "accounts/index";
    }

    @GetMapping("/{id}/edit")
    public String editPage(@AuthenticationPrincipal MemberPrincipal me,
                           @PathVariable("id") long accountId,
                           Model model) {
        Account account = accountService.require(me.getFamilyId(), accountId);
        addEditModel(me, model, account);
        model.addAttribute("insurancePolicy",
                insurancePolicyMapper.findByAccount(accountId).orElse(null));
        return "accounts/edit";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal MemberPrincipal me,
                         @ModelAttribute AccountForm form) {
        Account toCreate = form.toAccount();
        // v1.5.2 · 建户向导没有「平台」输入框:用户未填时,从所选模板带出平台默认值 → 打标默认即与真实账户一致(item6)
        if (toCreate.getPlatformTag() == null && toCreate.getTemplateId() != null) {
            accountTemplateService.find(toCreate.getTemplateId())
                    .map(AccountTemplate::getPlatform)
                    .filter(p -> p != null && !p.isBlank())
                    .ifPresent(toCreate::setPlatformTag);
        }
        Account created = accountService.create(me, toCreate);
        // v0.17 · 保险账户落保单登记旁表(仅 INSURANCE)
        if (created.getType() == AccountType.INSURANCE) {
            InsurancePolicy policy = form.toPolicy(created.getId());
            if (policy.hasAnyField()) {
                insurancePolicyMapper.upsert(policy);
            }
        }
        return "redirect:/accounts";
    }

    @PostMapping("/{id}/edit")
    public String edit(@AuthenticationPrincipal MemberPrincipal me,
                       @PathVariable("id") long accountId,
                       @ModelAttribute AccountForm form) {
        accountService.update(me, accountId, form.toAccount());
        // v0.17 · 保单旁表:保险账户 upsert;若改成非保险则清理旁表(校验归属由 update 完成)
        Account acc = accountService.require(me.getFamilyId(), accountId);
        if (acc.getType() == AccountType.INSURANCE) {
            insurancePolicyMapper.upsert(form.toPolicy(accountId));
        } else {
            insurancePolicyMapper.deleteByAccount(accountId);
        }
        return "redirect:/accounts";
    }

    @PostMapping("/{id}/archive")
    public String archive(@AuthenticationPrincipal MemberPrincipal me,
                          @PathVariable("id") long accountId) {
        accountService.archive(me, accountId);
        return "redirect:/accounts";
    }

    @PostMapping("/{id}/restore")
    public String restore(@AuthenticationPrincipal MemberPrincipal me,
                          @PathVariable("id") long accountId) {
        accountService.restore(me, accountId);
        return "redirect:/accounts?archived=true";
    }

    /** v0.2 FR-30 · 账户详情页(账本视角) */
    @GetMapping("/{id}")
    public String detail(@AuthenticationPrincipal MemberPrincipal me,
                         @PathVariable("id") long accountId,
                         @RequestParam(name = "type", required = false) String filterType,
                         @RequestParam(name = "range", required = false) Integer rangeMonths,
                         @RequestParam(name = "q", required = false) String keyword,
                         Model model) {
        var detail = accountDetailService.detail(me.getFamilyId(), accountId,
                filterType, rangeMonths, keyword);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("detail", detail);
        model.addAttribute("filterType", filterType == null ? "ALL" : filterType.toUpperCase());
        model.addAttribute("rangeMonths", rangeMonths == null ? 12 : rangeMonths);
        model.addAttribute("keyword", keyword == null ? "" : keyword);
        // v0.17 · 保险账户带保单登记(非保险为 null,详情页不渲染保单段)
        model.addAttribute("insurancePolicy",
                insurancePolicyMapper.findByAccount(accountId).orElse(null));
        // v1.20 · 这个账户属于哪个分组(null = 未分组)· 顺带给出去管理的入口
        model.addAttribute("accountGroupName",
                accountGroupService.occupiedBy(me.getFamilyId(), null).get(accountId));
        return "accounts/detail";
    }

    /** v0.2 FR-31 · 单账户账本 CSV 导出 */
    @GetMapping("/{id}/ledger.csv")
    public void exportLedger(@AuthenticationPrincipal MemberPrincipal me,
                             @PathVariable("id") long accountId,
                             HttpServletResponse response) throws java.io.IOException {
        Account account = accountService.require(me.getFamilyId(), accountId);
        response.setContentType("text/csv; charset=UTF-8");
        String safeName = account.getDisplayName().replaceAll("[\\\\/:*?\"<>|]", "_");
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(safeName + "-ledger.csv",
                        java.nio.charset.StandardCharsets.UTF_8));
        ledgerExporter.writeAccountLedger(me.getFamilyId(), accountId, response.getOutputStream());
    }

    private void addModel(MemberPrincipal me, Model model, boolean includeArchived, boolean showWizard) {
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        var rows = accountService.listRows(me.getFamilyId(), includeArchived);
        model.addAttribute("rows", rows);
        model.addAttribute("summary", accountService.summarize(me.getFamilyId()));
        model.addAttribute("templates", accountTemplateService.listOrdered());
        model.addAttribute("members", memberMapper.findActiveByFamily(me.getFamilyId()));
        model.addAttribute("types", AccountType.values());
        model.addAttribute("currencies", new String[]{"CNY", "USD", "HKD"});
        // 按 owner 分组 + 颜色(手机版 card 分组 · 与填报页一致)
        var ownerGroups = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        r -> r.ownerName() == null ? "共同" : r.ownerName(),
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        model.addAttribute("ownerGroups", ownerGroups);
        var ownerColorMap = new java.util.LinkedHashMap<String, Integer>();
        int colorIdx = 0;
        for (String key : ownerGroups.keySet()) ownerColorMap.put(key, colorIdx++);
        model.addAttribute("ownerColorMap", ownerColorMap);
        // v0.15.x · 券商托管徽章:账户id → 厂商中文名
        var brokerLinks = new java.util.LinkedHashMap<Long, String>();
        // v1.18 · 同步失败的账户:账户id → 失败说明。
        // 为什么标在列表上:失败以前只在「账户 → 券商」二级页里看得见,而那页没人天天点 ——
        // 生产上富途断了两天没人发现(见 v1.17.3)。列表是每次看账户的必经处,标在这里才拦得住。
        var brokerFailures = new java.util.LinkedHashMap<Long, String>();
        for (var bl : brokerLinkMapper.findByFamily(me.getFamilyId())) {
            brokerLinks.put(bl.getAccountId(), bl.getVendor().getLabel());
            if (bl.isEnabled() && bl.getLastStatus() != null
                    && bl.getLastStatus().startsWith("同步失败")) {
                brokerFailures.put(bl.getAccountId(), bl.getLastStatus());
            }
        }
        model.addAttribute("brokerLinks", brokerLinks);
        model.addAttribute("brokerFailures", brokerFailures);
        model.addAttribute("form", new AccountForm());
        model.addAttribute("allCategories", productCategoryService.listAll());
        model.addAttribute("insuranceSubTypes", InsuranceSubType.values()); // v0.17 保险子类型下拉
        model.addAttribute("includeArchived", includeArchived);
        model.addAttribute("showWizard", showWizard);
    }

    private void addEditModel(MemberPrincipal me, Model model, Account account) {
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        model.addAttribute("rows", accountService.listRows(me.getFamilyId(), false));
        // v1.15 FR-382 · 编辑表单的候选 = 活跃 ∪ 当前主理人。
        // 纯活跃列表会让「主理人已归档」的账户在下拉里选不中自己 → 保存别的字段时被静默改派成「共同」。
        model.addAttribute("members",
                memberDirectory.selectableWith(me.getFamilyId(), account.getPrimaryOwnerMemberId()));
        model.addAttribute("types", AccountType.values());
        model.addAttribute("currencies", new String[]{"CNY", "USD", "HKD"});
        model.addAttribute("applicableCategories",
                productCategoryService.findApplicableFor(account.getType()));
        model.addAttribute("currentCategory",
                productCategoryService.findByCode(account.getProductCategoryCode()).orElse(null));
        model.addAttribute("insuranceSubTypes", InsuranceSubType.values()); // v0.17 保险子类型下拉
        // v1.1 · 资产透视打标下拉(大类默认派生提示 + 行业清单 + 平台常用建议)
        model.addAttribute("assetClasses", com.family.finance.domain.lens.AssetClass.values());
        model.addAttribute("industryTags", com.family.finance.domain.lens.IndustryTag.values());
        model.addAttribute("assetClassDefault",
                com.family.finance.domain.lens.AssetClass.defaultFor(account.getType(), account.getProductCategoryCode()));
        model.addAttribute("platformSuggestions", PLATFORM_SUGGESTIONS);
        model.addAttribute("purposeTags", com.family.finance.domain.lens.PurposeTag.values());
    }

    /** v1.1 · 平台/机构常用建议(datalist · 自由文本可另填) */
    private static final String[] PLATFORM_SUGGESTIONS = {
            "招商银行", "工商银行", "建设银行", "中国银行", "支付宝 · 蚂蚁财富", "微信 · 理财通",
            "富途证券", "老虎证券", "华泰证券", "中信证券", "天天基金", "京东金融",
            "中国人寿", "平安保险", "泰康保险", "币安", "欧易", "其他"
    };

    @Data
    public static class AccountForm {
        private Long templateId;
        private String displayName;
        private AccountType type = AccountType.CASH;
        private String currency = "CNY";
        private Long primaryOwnerMemberId;
        private Long defaultPaymentSourceAccountId;
        private Integer displayOrder;
        /** v0.2 · 产品类目 code(FR-40d)*/
        private String productCategoryCode;
        /** v0.2 · 风险等级覆盖(NULL = 沿用类目)· FR-40d */
        private Integer riskLevelOverride;
        /** v0.6 · 负债类型(仅 LOAN · 选填)· FR-103 */
        private String loanKind;
        /** v0.6 · 负债年利率 %(仅 LOAN · 选填)· FR-103 */
        private java.math.BigDecimal annualRatePct;
        /** v0.8 · 预期年化收益率 %(选填 · NULL=回落品类 benchmark)· 预实 FR-152 */
        private java.math.BigDecimal expectedReturnPct;

        // ---------- v1.1 · 资产透视维度打标(全选填 · 直落 account 三列)----------
        private String assetClass;
        private String platformTag;
        private String industryTag;
        private String purposeTag;

        // ---------- v0.17 · 保险保单登记(仅 INSURANCE · 全选填 · 落旁表 account_insurance_policy)----------
        private String insuranceKind;
        private String insurer;
        private String policyNo;
        private String policyHolder;
        private String insuredPerson;
        private java.math.BigDecimal coverageAmount;
        private java.math.BigDecimal premiumAmount;
        private String premiumFrequency;
        private Integer premiumTermsTotal;
        private Integer premiumTermsPaid;
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        private java.time.LocalDate policyEffectiveDate;
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        private java.time.LocalDate policyMaturityDate;

        Account toAccount() {
            return Account.builder()
                    .templateId(templateId)
                    .displayName(displayName)
                    .type(type)
                    .currency(currency)
                    .primaryOwnerMemberId(primaryOwnerMemberId)
                    .defaultPaymentSourceAccountId(defaultPaymentSourceAccountId)
                    .displayOrder(displayOrder)
                    .productCategoryCode(productCategoryCode)
                    .riskLevelOverride(riskLevelOverride)
                    .loanKind(loanKind)
                    .annualRatePct(annualRatePct)
                    .expectedReturnPct(expectedReturnPct)
                    .assetClass(blankToNull(assetClass))
                    .platformTag(blankToNull(platformTag))
                    .industryTag(blankToNull(industryTag))
                    .purposeTag(blankToNull(purposeTag))
                    .build();
        }

        /** v0.17 · 组装保单旁表对象(空串归一为 null,便于 hasAnyField 判空) */
        InsurancePolicy toPolicy(Long accountId) {
            return InsurancePolicy.builder()
                    .accountId(accountId)
                    .insuranceKind(blankToNull(insuranceKind))
                    .insurer(blankToNull(insurer))
                    .policyNo(blankToNull(policyNo))
                    .policyHolder(blankToNull(policyHolder))
                    .insuredPerson(blankToNull(insuredPerson))
                    .coverageAmount(coverageAmount)
                    .premiumAmount(premiumAmount)
                    .premiumFrequency(blankToNull(premiumFrequency))
                    .premiumTermsTotal(premiumTermsTotal)
                    .premiumTermsPaid(premiumTermsPaid)
                    .policyEffectiveDate(policyEffectiveDate)
                    .policyMaturityDate(policyMaturityDate)
                    .build();
        }

        private static String blankToNull(String s) {
            return (s == null || s.isBlank()) ? null : s.trim();
        }
    }
}
