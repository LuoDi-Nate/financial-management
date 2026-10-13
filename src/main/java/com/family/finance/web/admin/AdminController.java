package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLog;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.backup.BackupLog;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.fx.FxRate;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.repository.AccountTemplateMapper;
import com.family.finance.repository.AuditMapper;
import com.family.finance.repository.BackupLogMapper;
import com.family.finance.repository.CashFlowCategoryMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.AdminService;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.FamilyService;
import com.family.finance.service.FxService;
import com.family.finance.service.PeriodService;
import com.family.finance.service.ProductCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * /admin · 管理总入口 + 12 个子页(详见 PRD § FR-20)。
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final FamilyService familyService;
    private final FamilyMapper familyMapper;
    private final MemberMapper memberMapper;
    private final AccountTemplateMapper accountTemplateMapper;
    private final CashFlowCategoryMapper cashFlowCategoryMapper;
    private final PeriodService periodService;
    private final BackupLogMapper backupLogMapper;
    private final AuditMapper auditMapper;
    private final FxService fxService;
    private final AdminService adminService;
    private final com.family.finance.service.backup.BackupNowService backupNowService;
    /** v1.15 FR-382 · 名字映射的唯一出口(含已归档);仅活跃列表只留给"面向未来的选择/分母" */
    private final com.family.finance.service.member.MemberDirectory memberDirectory;
    private final com.family.finance.service.member.MemberReferenceScanner referenceScanner;
    private final com.family.finance.service.PeriodOpener periodOpener;
    private final AuditLogService auditLogService;
    private final ProductCategoryService productCategoryService;
    // v0.4
    private final com.family.finance.repository.RebalanceAdviceCacheMapper rebalanceCacheMapper;
    // v0.4.18 · 数值阈值可编辑(详 prd §22)
    private final com.family.finance.service.config.FamilyConfigService configService;
    // v1.1.x · 旭日环级配色方案(A-E · calc-tweaks 可配)
    private final com.family.finance.service.lens.LensMetaService lensMetaService;
    // v0.8 · 我关心的指标(可配置指标集 · 详 prd §FR-149/150)
    private final com.family.finance.service.MetricPrefsService metricPrefsService;
    // v1.9 · 版本落后检查(只查不改)
    private final com.family.finance.service.update.UpdateCheckService updateCheckService;
    /** v1.18.2 · 账目对账(只读扫描) */
    private final com.family.finance.service.reconcile.ReconciliationScanService reconciliationScanService;

    /** v1.9 · 当前发布版本(与 nav 徽记、/health 同源)· @Value 字段注入,不能当 handler 参数 */
    @org.springframework.beans.factory.annotation.Value("${app.version:dev}")
    private String appVersion;

    // ---------------------------------------------------------------------
    // 1. /admin · 总览
    // ---------------------------------------------------------------------
    @GetMapping
    public String hub(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        Family family = familyService.require(me.getFamilyId());
        Period current = periodService.findBalancePeriod(me.getFamilyId()).orElse(null);
        BackupLog lastBackup = backupLogMapper.latest(me.getFamilyId()).orElse(null);
        List<FxRate> fx = fxService.recent(me.getFamilyId(), 5);
        model.addAttribute("family", family);
        model.addAttribute("currentPeriod", current);
        model.addAttribute("lastBackup", lastBackup);
        model.addAttribute("fxRecent", fx);
        // v1.9 · 版本卡:全部读缓存,**这里不出网**(出网只在定时器和「立即检查」里)
        model.addAttribute("updateInfo", updateCheckService.cached(me.getFamilyId()));
        model.addAttribute("updateEnabled", updateCheckService.enabled(me.getFamilyId()));
        var updAtt = updateCheckService.lastAttempt(me.getFamilyId());
        model.addAttribute("updateAttempt", updAtt);
        // 时间在服务端格式化好 —— Instant 在 Thymeleaf 里要绕 zone,不值当
        model.addAttribute("updateCheckedAt", updAtt.at() == null ? null
                : java.time.LocalDateTime.ofInstant(updAtt.at(), java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")));
        model.addAttribute("updateRepo", com.family.finance.service.update.UpdateCheckService.REPO);
        return "admin/index";
    }

    // ---------------------------------------------------------------------
    // v1.9 · 版本检查:立即检查 / 开关
    // ---------------------------------------------------------------------

    /** 手动触发一次检查(会出网)· 这是**唯一**一条用户能主动触发出网的路径。 */
    @PostMapping("/update-check/now")
    public String updateCheckNow(@AuthenticationPrincipal MemberPrincipal me,
                                 RedirectAttributes ra) {
        var info = updateCheckService.checkNow(me.getFamilyId(), appVersion);
        var att = updateCheckService.lastAttempt(me.getFamilyId());
        ra.addFlashAttribute("flash", att.ok()
                ? (info.hasUpdate() ? "检查完成 · 有新版 " + info.latest() : "检查完成 · 已是最新")
                : "检查失败:" + (att.error() == null ? "未知原因" : att.error()));
        return "redirect:/admin?tab=version";
    }

    /** 开 / 关自动检查。关掉后零后台请求,圆点立刻消失。 */
    @PostMapping("/update-check/toggle")
    public String updateCheckToggle(@AuthenticationPrincipal MemberPrincipal me,
                                    @RequestParam(defaultValue = "false") boolean enabled,
                                    RedirectAttributes ra) {
        updateCheckService.setEnabled(me.getFamilyId(), enabled);
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family", me.getFamilyId(), "自动检查更新=" + (enabled ? "开" : "关"));
        ra.addFlashAttribute("flash", enabled ? "已开启自动检查更新" : "已关闭 · 不再有任何后台请求");
        return "redirect:/admin?tab=version";
    }

    // ---------------------------------------------------------------------
    // 2. /admin/family · 家庭设置(品牌名 + 本位币 + 周期类型 + Logo)
    // ---------------------------------------------------------------------
    @GetMapping("/family")
    public String family(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("family", familyService.require(me.getFamilyId()));
        // v0.2 FR-1/FR-34 · 预设图标白名单(顺序固定供 gallery 渲染)
        model.addAttribute("logoPresets", java.util.List.of("icon1", "icon2", "icon3", "icon4"));
        return "admin/family";
    }

    // v0.4 FR-61a · 切换 CPI 假设(2/3/5%)· 从 dashboard 顶部 widget POST
    @PostMapping("/family/cpi")
    public String updateCpi(@AuthenticationPrincipal MemberPrincipal me,
                            @RequestParam("cpi") java.math.BigDecimal cpi) {
        if (cpi == null || cpi.signum() < 0 || cpi.compareTo(new java.math.BigDecimal("20")) > 0) {
            return "redirect:/dashboard";
        }
        familyMapper.updateCpiAssumption(me.getFamilyId(), cpi);
        return "redirect:/dashboard";
    }

    // v0.4 FR-62a · 切换配置锚(预置 5 选 1)· 从 reports 配置 diff 下拉 POST
    @PostMapping("/family/anchor")
    public String updateAnchor(@AuthenticationPrincipal MemberPrincipal me,
                               @RequestParam("anchor") String anchor) {
        if (!com.family.finance.domain.allocation.AnchorCode.isValid(anchor)) {
            return "redirect:/reports";
        }
        familyMapper.updateAllocationAnchor(me.getFamilyId(), anchor.toUpperCase());
        // 切锚 = AI 调仓缓存失效
        rebalanceCacheMapper.deleteByFamily(me.getFamilyId());
        return "redirect:/reports#allocation-diff";
    }

    // v0.4 FR-62b · 切换风险偏好(LLM 调仓 prompt 输入)· 从 /admin/family 下拉
    @PostMapping("/family/risk-appetite")
    public String updateRiskAppetite(@AuthenticationPrincipal MemberPrincipal me,
                                     @RequestParam("appetite") String appetite,
                                     RedirectAttributes ra) {
        if (!com.family.finance.domain.allocation.RiskAppetite.isValid(appetite)) {
            ra.addFlashAttribute("flash", "非法的风险偏好: " + appetite);
            return "redirect:/admin/family";
        }
        familyMapper.updateRiskAppetite(me.getFamilyId(), appetite.toUpperCase());
        rebalanceCacheMapper.deleteByFamily(me.getFamilyId());
        ra.addFlashAttribute("flash", "已保存");
        return "redirect:/admin/family";
    }

    @PostMapping("/family")
    public String updateFamily(@AuthenticationPrincipal MemberPrincipal me,
                               @RequestParam String name,
                               @RequestParam String brandText,
                               @RequestParam String baseCurrency,
                               @RequestParam String periodType,
                               RedirectAttributes ra) {
        Family f = familyService.require(me.getFamilyId());
        PeriodType newType = PeriodType.valueOf(periodType);
        // PRD FR-4:切换周期类型前,必须确认无 OPEN 周期
        if (f.getPeriodType() != newType && periodService.findBalancePeriod(me.getFamilyId()).isPresent()) {
            ra.addFlashAttribute("flash", "切换周期类型失败:存在 OPEN 周期,请先关闭");
            return "redirect:/admin/family";
        }
        f.setName(name);
        f.setBrandText(brandText);
        f.setBaseCurrency(baseCurrency);
        f.setPeriodType(newType);
        familyMapper.update(f);
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family", me.getFamilyId(), "家庭设置更新:%s · %s · %s".formatted(name, baseCurrency, periodType));
        ra.addFlashAttribute("flash", "已保存");
        return "redirect:/admin/family";
    }

    // ---------------------------------------------------------------------
    // 3. /admin/members · 成员
    // ---------------------------------------------------------------------
    @GetMapping("/members")
    public String members(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("me", me);
        // v1.15 FR-381 · 名单含已归档 —— 归档的人得看得见,否则"撤销归档"没有入口
        List<Member> all = memberDirectory.listAll(me.getFamilyId());
        model.addAttribute("members", all);
        model.addAttribute("activeCount", all.stream().filter(m -> !m.isArchived()).count());
        // v1.15 FR-383 · 引用扫描只对已归档成员做:删除入口本来就只对归档后的人开放,
        // 而且这样每次开页最多扫几个人 —— 不会因为一张管理页把 13 张表 × 全体成员都 COUNT 一遍。
        java.util.Map<Long, com.family.finance.service.member.MemberReferenceScanner.Scan> refs =
                new java.util.LinkedHashMap<>();
        for (Member m : all) {
            if (m.isArchived()) {
                refs.put(m.getId(), referenceScanner.scan(me.getFamilyId(), m.getId()));
            }
        }
        model.addAttribute("refs", refs);
        return "admin/members";
    }

    /** v1.15 FR-380 · 修改登录名。改的是自己 → 本次会话会被就地作废,落到 /login?expired。 */
    @PostMapping("/members/{memberId}/username")
    public String renameUsername(@AuthenticationPrincipal MemberPrincipal me,
                                 @PathVariable long memberId,
                                 @RequestParam String username,
                                 RedirectAttributes ra) {
        try {
            adminService.renameUsername(me.getFamilyId(), memberId, username, me.getMemberId());
            ra.addFlashAttribute("flash", "登录名已改为:" + username.trim()
                    + "(该成员当前的登录状态已失效,需要用新登录名重新登录)");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("flash", "改登录名失败:" + ex.getMessage());
        }
        return "redirect:/admin/members";
    }

    /** v1.15 FR-381 · 归档成员 · 当场生效。 */
    @PostMapping("/members/{memberId}/archive")
    public String archiveMember(@AuthenticationPrincipal MemberPrincipal me,
                                @PathVariable long memberId,
                                RedirectAttributes ra) {
        try {
            adminService.archiveMember(me.getFamilyId(), memberId, me.getMemberId());
            ra.addFlashAttribute("flash", "已归档 —— ta 的登录已当场失效,名下账户与历史记录一条没动。");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("flash", "归档失败:" + ex.getMessage());
        }
        return "redirect:/admin/members";
    }

    /** v1.15 FR-381 · 撤销归档。 */
    @PostMapping("/members/{memberId}/restore")
    public String restoreMember(@AuthenticationPrincipal MemberPrincipal me,
                                @PathVariable long memberId,
                                RedirectAttributes ra) {
        try {
            adminService.restoreMember(me.getFamilyId(), memberId, me.getMemberId());
            ra.addFlashAttribute("flash", "已撤销归档,ta 可以重新登录了。");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("flash", "撤销归档失败:" + ex.getMessage());
        }
        return "redirect:/admin/members";
    }

    /** v1.15 FR-383 · 删除成员 —— 仅零引用可达,且要求手打登录名二次确认。 */
    @PostMapping("/members/{memberId}/delete")
    public String deleteMember(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable long memberId,
                               @RequestParam(required = false) String confirmUsername,
                               RedirectAttributes ra) {
        try {
            adminService.deleteMember(me.getFamilyId(), memberId, confirmUsername, me.getMemberId());
            ra.addFlashAttribute("flash", "已删除该成员。");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("flash", "删除失败:" + ex.getMessage());
        }
        return "redirect:/admin/members";
    }

    @PostMapping("/members/{memberId}")
    public String updateMember(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable long memberId,
                               @RequestParam String displayName,
                               @RequestParam(required = false) String roleLabel,
                               RedirectAttributes ra) {
        adminService.updateMemberProfile(me.getFamilyId(), memberId,
                displayName, blankToNull(roleLabel), me.getMemberId());
        // BUG-FIX(2026-05-11):若改的是自己,刷新 SecurityContext 的 MemberPrincipal,
        // 让顶栏 me.displayName 立刻看到新名(否则要等会话过期重登才生效)
        if (memberId == me.getMemberId()) {
            Member fresh = memberMapper.findById(me.getFamilyId(), memberId).orElseThrow();
            MemberPrincipal refreshed = new MemberPrincipal(fresh);
            Authentication old = SecurityContextHolder.getContext().getAuthentication();
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(refreshed, old.getCredentials(), refreshed.getAuthorities()));
        }
        ra.addFlashAttribute("flash", "已保存:" + displayName);
        return "redirect:/admin/members";
    }

    @PostMapping("/members/{memberId}/reset-password")
    public String resetPassword(@AuthenticationPrincipal MemberPrincipal me,
                                @PathVariable long memberId,
                                RedirectAttributes ra) {
        String temp = adminService.resetPassword(me.getFamilyId(), memberId, me.getMemberId());
        Member m = memberMapper.findById(me.getFamilyId(), memberId).orElseThrow();
        ra.addFlashAttribute("tempPassword", temp);
        ra.addFlashAttribute("tempPasswordFor", m.getDisplayName());
        ra.addFlashAttribute("tempPasswordMemberId", memberId);
        return "redirect:/admin/members";
    }

    /** 添加成员(PRD §7.9 维护性变更:把原"v0.3 才开放"提前到 v0.1 末)。 */
    @PostMapping("/members")
    public String createMember(@AuthenticationPrincipal MemberPrincipal me,
                               @RequestParam String username,
                               @RequestParam String displayName,
                               @RequestParam(required = false) String roleLabel,
                               RedirectAttributes ra) {
        try {
            String temp = adminService.createMember(me.getFamilyId(),
                    username, displayName, blankToNull(roleLabel), me.getMemberId());
            ra.addFlashAttribute("tempPassword", temp);
            ra.addFlashAttribute("tempPasswordFor", displayName);
            ra.addFlashAttribute("flash", "已添加成员:" + displayName);
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("flash", "添加失败:" + ex.getMessage());
        }
        return "redirect:/admin/members";
    }

    // ---------------------------------------------------------------------
    // 4. /admin/accounts · 直接复用 /accounts(已存在)
    // ---------------------------------------------------------------------
    @GetMapping("/accounts")
    public String adminAccountsRedirect() {
        return "redirect:/accounts";
    }

    // ---------------------------------------------------------------------
    // 5. /admin/account-templates · 只读
    // ---------------------------------------------------------------------
    @GetMapping("/account-templates")
    public String accountTemplates(Model model) {
        model.addAttribute("templates", accountTemplateMapper.listOrdered());
        return "admin/account-templates";
    }

    // ---------------------------------------------------------------------
    // 6. /admin/cash-flow-categories · 只读
    // ---------------------------------------------------------------------
    @GetMapping("/cash-flow-categories")
    public String cashFlowCategories(Model model) {
        model.addAttribute("categories", cashFlowCategoryMapper.listOrdered());
        return "admin/cash-flow-categories";
    }

    // ---------------------------------------------------------------------
    // 6b. /admin/product-categories · v0.2 产品类目(只读)
    // ---------------------------------------------------------------------
    @GetMapping("/product-categories")
    public String productCategories(Model model) {
        model.addAttribute("categories", productCategoryService.listAll());
        return "admin/product-categories";
    }

    // ---------------------------------------------------------------------
    // 7. /admin/periods · 周期管理 + 重新打开
    // ---------------------------------------------------------------------
    @GetMapping("/periods")
    public String periods(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(value = "page", defaultValue = "0") int page,
                          Model model) {
        int pageSize = 24;
        int total = periodService.countPeriods(me.getFamilyId());
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        List<Period> periods = periodService.findPaged(me.getFamilyId(), pageSize, safePage * pageSize);
        model.addAttribute("periods", periods);
        model.addAttribute("currentPeriod", periodService.findBalancePeriod(me.getFamilyId()).orElse(null));
        // v0.5 修 · 分页(beta 测试数据曾到 2032 · 88 期翻不到当前)
        model.addAttribute("page", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalPeriods", total);

        // v1.23 FR-610 / FR-616 · 关账节奏配置 + 「什么时候关」预告
        com.family.finance.domain.family.Family fam = familyService.require(me.getFamilyId());
        java.time.LocalDate today = java.time.LocalDate.now();
        model.addAttribute("closeDelayDays", fam.closeDelayDaysOrZero());
        model.addAttribute("autoCloseEnabled", fam.autoCloseOrDefault());
        // 每个仍 OPEN 的期算出它的关账截止日 —— 不能让用户猜(FR-616)
        java.util.Map<Long, java.time.LocalDate> closeDueById = new java.util.LinkedHashMap<>();
        java.util.Map<Long, Long> graceLeftById = new java.util.LinkedHashMap<>();
        for (Period open : periodService.findRecordableOpen(me.getFamilyId())) {
            closeDueById.put(open.getId(), periodService.graceDeadline(fam, open));
            graceLeftById.put(open.getId(), periodService.graceDaysLeft(fam, open, today));
        }
        model.addAttribute("closeDueById", closeDueById);
        model.addAttribute("graceLeftById", graceLeftById);
        return "admin/periods";
    }

    /**
     * v1.23 FR-610 · 关账节奏:立即 / T+2 / T+5 / 我自己关。
     *
     * <p>四个档位在页面上是一个单选组,所以这里只收一个字符串,由服务端翻译成
     * (宽限天数, 自动开关)两个值 —— 前端传两个参数会产生「选了手动但宽限还是 2」
     * 这种读不出意图的中间态。</p>
     */
    @PostMapping("/family/close-rhythm")
    public String updateCloseRhythm(@AuthenticationPrincipal MemberPrincipal me,
                                    @RequestParam String rhythm,
                                    RedirectAttributes ra) {
        int delay;
        boolean auto;
        switch (rhythm) {
            case "T0"     -> { delay = 0; auto = true;  }
            case "T2"     -> { delay = 2; auto = true;  }
            case "T5"     -> { delay = 5; auto = true;  }
            case "MANUAL" -> { delay = 0; auto = false; }
            default -> {
                ra.addFlashAttribute("flash", "未知的关账节奏:" + rhythm);
                return "redirect:/admin/periods";
            }
        }
        familyMapper.updateCloseRhythm(me.getFamilyId(), delay, auto);
        auditLogService.record(me.getFamilyId(), me.getMemberId(),
                com.family.finance.domain.audit.AuditLogType.SYSTEM, "family", me.getFamilyId(),
                "关账节奏改为 " + rhythm + "(宽限 " + delay + " 天 · 自动关账 " + auto + ")");
        ra.addFlashAttribute("flash", switch (rhythm) {
            case "T0"     -> "已改为「立即关账」:新月份一开始,上月立刻锁上。";
            case "T2"     -> "已改为「月底过完再给 2 天」:上月的报表、AI 月报、指标也会晚 2 天出。";
            case "T5"     -> "已改为「月底过完再给 5 天」:上月的报表、AI 月报、指标也会晚 5 天出。";
            default       -> "已改为「我自己关」:到下下个月开始时,系统仍会兜底关掉它。";
        });
        return "redirect:/admin/periods";
    }

    @PostMapping("/periods/open-next")
    public String openNextPeriod(@AuthenticationPrincipal MemberPrincipal me,
                                 RedirectAttributes ra) {
        try {
            Period period = periodOpener.openNextNow(me.getFamilyId());
            ra.addFlashAttribute("flash",
                    "已开启新周期:" + period.getPeriodStart() + "(snapshot_todo / LOAN 预填已生成)");
        } catch (Exception ex) {
            ra.addFlashAttribute("flash", "开启失败:" + ex.getMessage());
        }
        return "redirect:/admin/periods";
    }

    @PostMapping("/periods/{periodId}/force-close")
    public String forceClose(@AuthenticationPrincipal MemberPrincipal me,
                              @PathVariable long periodId,
                              RedirectAttributes ra) {
        try {
            int filled = periodService.forceClose(me.getFamilyId(), periodId, me.getMemberId());
            ra.addFlashAttribute("flash", "已强制关账,代填 " + filled + " 个账户的余额(延续上期末);触发指标重算");
        } catch (Exception ex) {
            ra.addFlashAttribute("flash", "强制关账失败:" + ex.getMessage());
        }
        return "redirect:/admin/periods";
    }

    @PostMapping("/periods/{periodId}/reopen")
    public String reopenPeriod(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable long periodId,
                               @RequestParam String reason,
                               RedirectAttributes ra) {
        if (reason == null || reason.isBlank()) {
            ra.addFlashAttribute("flash", "重开失败:必须填理由");
            return "redirect:/admin/periods";
        }
        periodService.reopen(me.getFamilyId(), periodId, reason);
        ra.addFlashAttribute("flash", "周期已重新打开");
        return "redirect:/admin/periods";
    }

    // 8. /admin/reminders · v0.4.14 FR-63d 迁至 NotificationSettingsController(填报模板 + 短信强提醒)

    // ---------------------------------------------------------------------
    // 9. /admin/fx · 汇率维护
    // ---------------------------------------------------------------------
    @GetMapping("/fx")
    public String fx(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("family", familyService.require(me.getFamilyId()));
        model.addAttribute("rates", fxService.recent(me.getFamilyId(), 24));
        model.addAttribute("periods", periodService.findLatest(me.getFamilyId(), 12));
        return "admin/fx";
    }

    @PostMapping("/fx/override")
    public String overrideFx(@AuthenticationPrincipal MemberPrincipal me,
                             @RequestParam long periodId,
                             @RequestParam String quoteCurrency,
                             @RequestParam BigDecimal rate,
                             RedirectAttributes ra) {
        fxService.manualOverride(me.getFamilyId(), periodId, quoteCurrency, rate, me.getMemberId());
        ra.addFlashAttribute("flash", "已手填覆盖 %s @ period#%d".formatted(quoteCurrency, periodId));
        return "redirect:/admin/fx";
    }

    @PostMapping("/fx/fetch")
    public String fetchFx(@AuthenticationPrincipal MemberPrincipal me, RedirectAttributes ra) {
        try {
            fxService.fetchForLatestPeriods(me.getFamilyId());
            ra.addFlashAttribute("flash", "拉取完成");
        } catch (Exception e) {
            ra.addFlashAttribute("flash", "拉取失败:" + e.getMessage());
        }
        return "redirect:/admin/fx";
    }

    // ---------------------------------------------------------------------
    // 10. /admin/backup · 备份状态
    // ---------------------------------------------------------------------
    @GetMapping("/backup")
    public String backup(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("logs", backupLogMapper.recent(me.getFamilyId(), 8));
        model.addAttribute("now", LocalDateTime.now());
        // v1.32 · 部署方式由这里给,不再按「最新一条记录的类型」猜 —— 最新一条是「手动」时 Docker 用户会看到 systemd 的文案
        model.addAttribute("backupMode", backupNowService.isDocker() ? "docker" : "systemd");
        model.addAttribute("backupDir", backupNowService.dir().toString());
        model.addAttribute("backupDirWritable", backupNowService.dirWritable());
        return "admin/backup";
    }

    /**
     * v1.32 FR-1170 ~ 1173 · 立即备份。同步跑完再回来(几秒到几十秒):成功 / 失败都有一行「手动」记录,
     * 失败原因是 mysqldump 的原话;正在备份 / 不到 1 分钟 → 不跑,直接说为什么。
     */
    @PostMapping("/backup/now")
    public String backupNow(@AuthenticationPrincipal MemberPrincipal me, RedirectAttributes ra) {
        try {
            var r = backupNowService.runNow(me.getFamilyId(), me.getMemberId());
            if (r.ok()) {
                ra.addFlashAttribute("backupNote", "已备份 · " + r.file() + " · " + (r.bytes() / 1024) + " KB");
            } else {
                ra.addFlashAttribute("backupError", "备份失败:" + r.error());
            }
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("backupError", e.getMessage());
        }
        return "redirect:/admin/backup";
    }

    // ---------------------------------------------------------------------
    // 11. /admin/audit · 审计日志
    // ---------------------------------------------------------------------
    /**
     * v1.18.2 · 账目对账(**只读** · 不改任何数据)。
     *
     * <p>复盘 v1.18.1 那个会丢钱的 bug 时得出的结论:我们其实<b>有</b>探测器
     * ({@code ReconciliationCalculator.unexplained}),但它只在填报页对 CASH/LOAN 显示;
     * 而归因瀑布的「未归因」是残差定义、按构造恒等闭合 —— 错误会被它吸收成某个账户的「亏损」,
     * 永远不会报警。这页补的是那条<b>真会失败</b>的校验。</p>
     *
     * <p>放管理页而不是体检页:体检是给<b>资产配置</b>看病的(AI 解读),这条是<b>工程事实</b>
     * (账平不平),两者混在一起会让用户分不清「建议」和「错误」。</p>
     */
    @GetMapping("/reconcile")
    public String reconcile(@AuthenticationPrincipal MemberPrincipal me,
                            @RequestParam(value = "months", required = false) Integer months,
                            Model model) {
        int lookback = months == null ? 36 : Math.max(1, Math.min(months, 120));
        model.addAttribute("report", reconciliationScanService.scan(me.getFamilyId(), lookback));
        model.addAttribute("lookback", lookback);
        return "admin/reconcile";
    }

    @GetMapping("/audit")
    public String audit(@AuthenticationPrincipal MemberPrincipal me,
                        @RequestParam(required = false) String type,
                        Model model) {
        List<AuditLog> rows = auditMapper.findByFamily(me.getFamilyId(), blankToNull(type), 100);
        // v0.2 · "由谁" 列展示成员真名而非 #id
        // v1.15 FR-382 · 含已归档 —— 审计日志是历史,归档一个人不该让他做过的操作变成无名氏
        java.util.Map<Long, String> memberNames = memberDirectory.nameMap(me.getFamilyId());
        model.addAttribute("rows", rows);
        model.addAttribute("memberNames", memberNames);
        model.addAttribute("filterType", type);
        model.addAttribute("availableTypes", AuditLogType.values());
        // v1.12 FR-351 · 查询开销诊断开关。挂在审计页而不是新开页面 —— PRD v1.12 §2 非目标写明「不加新页面」,
        // 且这东西的语义(留痕 / 排查)与审计同类。
        model.addAttribute("sqlProfiler", configService.getBoolean(me.getFamilyId(),
                com.family.finance.service.config.FamilyConfigService.K_SQL_PROFILER, false));
        return "admin/audit";
    }

    /**
     * v1.12 FR-351 · 查询开销诊断开关。
     *
     * <p>开着会让每个请求多写一段清单日志,所以页面上要说清「查完就关」。这里不做自动过期 ——
     * 自动关会让一次跨若干请求的排查半路失效,反而更难用;留痕交给 audit_log。</p>
     */
    @PostMapping("/audit/sql-profiler")
    public String saveSqlProfiler(@AuthenticationPrincipal MemberPrincipal me,
                                  @RequestParam(value = "enabled", defaultValue = "false") boolean enabled,
                                  org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        long fid = me.getFamilyId();
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_SQL_PROFILER,
                String.valueOf(enabled));
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE, "family_runtime_config", fid,
                "查询开销诊断 · " + (enabled ? "开启" : "关闭"));
        ra.addFlashAttribute("flash", enabled
                ? "查询开销诊断已开启 · 访问任意页面后,清单写在应用日志里(响应头 X-Sql-Count 是控制器阶段的条数)· 排查完请关掉"
                : "查询开销诊断已关闭");
        return "redirect:/admin/audit";
    }

    // ---------------------------------------------------------------------
    // 11.5 /admin/appearance · v1.17.2 · 显示与外观(个人偏好 · 不落库)
    //
    // 旭日配色原来挂在 calc-tweaks(「计算与提示常数」)里,编号「②.5」硬插在两条阈值之间 ——
    // 那页装的是影响数字的参数,配色是纯视觉,放错了地方,用户也找不到。
    // 现在独立成页,并按维护者拍板改成【个人偏好】:存 localStorage,不落库、无迁移;
    // 服务端仍把家庭旧值传下去当回落默认,老用户之前设的方案继续生效直到他自己改。
    // ---------------------------------------------------------------------
    @GetMapping("/appearance")
    public String appearance(@org.springframework.security.core.annotation.AuthenticationPrincipal
                             com.family.finance.auth.MemberPrincipal me, Model model) {
        model.addAttribute("me", me);
        model.addAttribute("lensPalette", lensMetaService.palette(me.getFamilyId()));   // 家庭旧值 = 回落默认
        return "admin/appearance";
    }

    // ---------------------------------------------------------------------
    // 12. /admin/calc-tweaks · v0.4.18 · 数值阈值可编辑(详 prd §22)
    // ---------------------------------------------------------------------
    @GetMapping("/calc-tweaks")
    public String calcTweaks(@org.springframework.security.core.annotation.AuthenticationPrincipal
                             com.family.finance.auth.MemberPrincipal me, Model model) {
        long fid = me.getFamilyId();
        com.family.finance.service.config.FamilyConfigService cs = configService;
        // ① 录入提示阈值(老 3 项 · 之前只读 · v0.4.18 起可编辑)
        model.addAttribute("smartTransfer", cs.getLong(fid, com.family.finance.service.config.FamilyConfigService.K_SMART_TRANSFER, 3000L));
        model.addAttribute("loanAbnormal",  cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_LOAN_ABNORMAL, 3.0));
        model.addAttribute("unexplainedEps", cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_UNEXPLAINED_EPSILON, 0.01));
        /* v1.26 · 再平衡「算执行了」的比例。v1.2 起就在读(RebalancePlanService.onTransfer),页面一直没入口,
           只能用代码默认 0.8(护栏 v126-CONFIG-KEY-HAS-HOME 查出来的缺口)。库里存小数、页面显示百分比。 */
        model.addAttribute("rebalanceMatchPct", rebalanceMatchPercent(
                cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_REBALANCE_MATCH_PCT, 0.8)));
        // ② 体检阈值(新 4 项)
        model.addAttribute("checkupConcentration", cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_CHECKUP_CONCENTRATION, 0.40));
        model.addAttribute("checkupHighRisk",      cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_CHECKUP_HIGH_RISK, 0.40));
        model.addAttribute("liquidBuffer",          cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_LIQUID_BUFFER, 1.5));
        model.addAttribute("emergencyMonths",       cs.getInt(fid, com.family.finance.service.config.FamilyConfigService.K_EMERGENCY_MONTHS, 6));
        // v1.1 · 透视集中度阈值(LENS-CON-1/2)
        model.addAttribute("lensIndustryConc", cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_LENS_INDUSTRY_CONC, 0.40));
        model.addAttribute("lensPlatformConc", cs.getDouble(fid, com.family.finance.service.config.FamilyConfigService.K_LENS_PLATFORM_CONC, 0.40));
        // ③ 会话
        model.addAttribute("rememberMeSeconds",     cs.getLong(fid, com.family.finance.service.config.FamilyConfigService.K_REMEMBER_ME_SECONDS, 2592000L));
        /* v1.24 FR-674 · 支出分析章节开关。默认开。
         * 运营参数走管理页、不写服务器配置文件 —— 这是这个项目定下的规矩。 */
        model.addAttribute("expenseAnalysisOn", cs.getBoolean(fid,
                com.family.finance.service.expense.NormalExpenseService.K_EXPENSE_ANALYSIS, true));
        return "admin/calc-tweaks";
    }

    /** v0.4.18 · ① 录入提示阈值保存 */
    @PostMapping("/calc-tweaks/entry-thresholds")
    public String saveEntryThresholds(@org.springframework.security.core.annotation.AuthenticationPrincipal
                                      com.family.finance.auth.MemberPrincipal me,
                                      @org.springframework.web.bind.annotation.RequestParam("smartTransfer") long smartTransfer,
                                      @org.springframework.web.bind.annotation.RequestParam("loanAbnormal") double loanAbnormal,
                                      @org.springframework.web.bind.annotation.RequestParam("unexplainedEps") double unexplainedEps,
                                      @org.springframework.web.bind.annotation.RequestParam(value = "rebalanceMatchPct", required = false) Integer rebalanceMatchPct,
                                      org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        long fid = me.getFamilyId();
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_SMART_TRANSFER, String.valueOf(Math.max(0L, smartTransfer)));
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_LOAN_ABNORMAL,  String.valueOf(Math.max(1.0, loanAbnormal)));
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_UNEXPLAINED_EPSILON, String.valueOf(Math.max(0.0, unexplainedEps)));
        if (rebalanceMatchPct != null) {
            configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_REBALANCE_MATCH_PCT,
                    rebalanceMatchFraction(rebalanceMatchPct));
        }
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE, "family_runtime_config", fid,
                "录入阈值 · smartTransfer=" + smartTransfer + " · loanAbnormal=" + loanAbnormal + " · epsilon=" + unexplainedEps
                        + (rebalanceMatchPct == null ? "" : " · 再平衡核销=" + rebalanceMatchFraction(rebalanceMatchPct)));
        ra.addFlashAttribute("flash", "录入阈值已保存");
        return "redirect:/admin/calc-tweaks";
    }

    /**
     * v1.24 FR-674 · 支出分析章节开关。
     *
     * <p>关掉之后报表页支出章节退回上一版的样子,<b>而且 §3.5 的回流读数一并消失</b>
     * (紧急储备并列读数 / 体检流动性 / 目标页应急金)—— 不留半开状态。
     * 「我关了支出分析,怎么别处还在冒常态月均」是最让人困惑的一种半开,
     * 所以级联收在 {@code NormalExpenseService} 的 Optional 里,不靠各页面各自判断。</p>
     */
    @PostMapping("/calc-tweaks/expense-analysis")
    public String saveExpenseAnalysis(@org.springframework.security.core.annotation.AuthenticationPrincipal
                                      com.family.finance.auth.MemberPrincipal me,
                                      @org.springframework.web.bind.annotation.RequestParam(
                                          value = "expenseAnalysisOn", defaultValue = "false") boolean on,
                                      org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        long fid = me.getFamilyId();
        configService.set(fid,
                com.family.finance.service.expense.NormalExpenseService.K_EXPENSE_ANALYSIS,
                String.valueOf(on));
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid, "支出分析章节 · " + (on ? "打开" : "关闭"));
        ra.addFlashAttribute("flash", on
                ? "支出分析已打开 —— 报表页支出那一章会显示归因、三分与常态月均"
                : "支出分析已关闭 —— 报表页支出那一章回到原来的样子,常态月均那几处读数也一并不显示");
        return "redirect:/admin/calc-tweaks";
    }

    /**
     * v1.26 · 再平衡核销比例:页面填百分比(50–100),库里存小数(RebalancePlanService 一直按小数读,默认 0.8)。
     * 下限 50%:再低就是「随便划一笔就算执行了」,执行率会虚高。
     */
    static String rebalanceMatchFraction(int percent) {
        int p = Math.max(50, Math.min(percent, 100));
        return java.math.BigDecimal.valueOf(p).movePointLeft(2).stripTrailingZeros().toPlainString();
    }

    static int rebalanceMatchPercent(double fraction) {
        return (int) Math.round(Math.max(0.5, Math.min(fraction, 1.0)) * 100);
    }

    /** v0.4.18 · ② 体检阈值保存 */
    @PostMapping("/calc-tweaks/checkup-thresholds")
    public String saveCheckupThresholds(@org.springframework.security.core.annotation.AuthenticationPrincipal
                                        com.family.finance.auth.MemberPrincipal me,
                                        @org.springframework.web.bind.annotation.RequestParam("concentration") double concentration,
                                        @org.springframework.web.bind.annotation.RequestParam("highRisk") double highRisk,
                                        @org.springframework.web.bind.annotation.RequestParam("liquidBuffer") double liquidBuffer,
                                        @org.springframework.web.bind.annotation.RequestParam("emergencyMonths") int emergencyMonths,
                                        @org.springframework.web.bind.annotation.RequestParam(value = "lensIndustryConc", defaultValue = "0.40") double lensIndustryConc,
                                        @org.springframework.web.bind.annotation.RequestParam(value = "lensPlatformConc", defaultValue = "0.40") double lensPlatformConc,
                                        org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        long fid = me.getFamilyId();
        // 防呆:百分比合理区间 / 月数 ≥ 1
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_CHECKUP_CONCENTRATION, String.valueOf(clamp01(concentration)));
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_CHECKUP_HIGH_RISK,     String.valueOf(clamp01(highRisk)));
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_LIQUID_BUFFER,         String.valueOf(Math.max(1.0, liquidBuffer)));
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_EMERGENCY_MONTHS,      String.valueOf(Math.max(1, Math.min(emergencyMonths, 24))));
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_LENS_INDUSTRY_CONC,    String.valueOf(clamp01(lensIndustryConc)));
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_LENS_PLATFORM_CONC,    String.valueOf(clamp01(lensPlatformConc)));
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE, "family_runtime_config", fid,
                "体检阈值 · concentration=" + concentration + " · highRisk=" + highRisk + " · liquidBuffer=" + liquidBuffer + "x · emergencyMonths=" + emergencyMonths);
        ra.addFlashAttribute("flash", "体检阈值已保存 · 下次诊断生效");
        return "redirect:/admin/calc-tweaks";
    }

    /** v0.4.18 · ③ 会话有效期保存(说明:下次 login 生效 · 在世 cookie 仍按旧时长) */
    @PostMapping("/calc-tweaks/session")
    public String saveSession(@org.springframework.security.core.annotation.AuthenticationPrincipal
                              com.family.finance.auth.MemberPrincipal me,
                              @org.springframework.web.bind.annotation.RequestParam("rememberMeSeconds") long rememberMeSeconds,
                              org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        long fid = me.getFamilyId();
        long safe = Math.max(86400L, Math.min(rememberMeSeconds, 365 * 86400L)); // 1 天 ~ 1 年
        configService.set(fid, com.family.finance.service.config.FamilyConfigService.K_REMEMBER_ME_SECONDS, String.valueOf(safe));
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE, "family_runtime_config", fid,
                "remember-me 有效期 = " + safe + "s · 下次 login 生效");
        ra.addFlashAttribute("flash", "会话有效期已保存 · 注意:已 login 用户的旧 cookie 仍按旧时长(下次 login 才用新值)");
        return "redirect:/admin/calc-tweaks";
    }

    // ---------------------------------------------------------------------
    // 13. /admin/metrics · v0.8 · 我关心的指标(dashboard / reports 共用勾选)
    // ---------------------------------------------------------------------
    @GetMapping("/metrics")
    public String metrics(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        Family family = familyService.require(me.getFamilyId());
        String prefs = family.getMetricPrefs();
        model.addAttribute("familyCatalog", com.family.finance.service.MetricPrefsService.FAMILY);
        model.addAttribute("accountCatalog", com.family.finance.service.MetricPrefsService.ACCOUNT);
        model.addAttribute("familyEnabled", metricPrefsService.enabled(prefs, "family"));
        model.addAttribute("accountEnabled", metricPrefsService.enabled(prefs, "account"));
        return "admin/metrics";
    }

    @PostMapping("/metrics")
    public String saveMetrics(@AuthenticationPrincipal MemberPrincipal me,
                              @RequestParam(value = "family", required = false) List<String> family,
                              @RequestParam(value = "account", required = false) List<String> account,
                              RedirectAttributes ra) {
        long fid = me.getFamilyId();
        // 必选项兜底:无论前端是否提交(mandatory 项 disabled 不提交),enabled() 都会强制纳入 mandatory 项
        String submitted = metricPrefsService.serialize(family, account);
        List<String> familyKeys = List.copyOf(metricPrefsService.enabled(submitted, "family"));
        List<String> accountKeys = List.copyOf(metricPrefsService.enabled(submitted, "account"));
        String json = metricPrefsService.serialize(familyKeys, accountKeys);
        familyMapper.updateMetricPrefs(fid, json);
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE, "family_metric_prefs", fid,
                "指标设置 · family=" + familyKeys + " · account=" + accountKeys);
        ra.addFlashAttribute("flash", "指标设置已保存 · dashboard 与报表已按新勾选展示");
        return "redirect:/admin/metrics";
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(v, 1.0));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
