package com.family.finance.service.checkup.llm;

import com.family.finance.service.llmtrace.AccountCodenames;
import com.family.finance.service.llmtrace.PromptSurface;
import com.family.finance.service.llmtrace.PromptTrace;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.domain.member.Member;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.member.MemberDirectory;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.FamilyService;
import com.family.finance.service.ProductCategoryService;
import com.family.finance.service.checkup.AccountDiagnose;
import com.family.finance.service.checkup.FamilyDiagnose;
import com.family.finance.service.checkup.rule.Advice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 综合智能诊断服务 · v0.2 FR-40c · 2026-05-10 修订(决策 20)
 *
 * <p>替代旧的 {@code LlmAdviceService.polish(...)} per-advice 模式。
 * 新方向:per-page 综合诊断 — 把全家完整画像 + 命中规则集合一次性送给 LLM,
 * 让它跨规则、跨账户做综合判断,返回 200-500 字综合诊断长文。
 *
 * <p>失败兜底从"静默"改为"明示":
 * 全部 client 失败时返回 {@code DiagnoseResult.unavailable(...)},前端显示
 * 「AI 暂时不可用,以下为规则硬数据」占位 + 刷新链接。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmDiagnoseService {

    /** v1.13 · 不再自己注入 {@code List<LlmClient>} 自己排序 —— 主备编排收口到路由 */
    private final LlmRouter llmRouter;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.service.review.RebalancePlanService rebalancePlanService;   // v1.2 执行率信号
    private final AuditLogService auditLogService;
    /**
     * v1.15 FR-382 · 脱敏映射必须**含已归档成员**。
     * 只拿活跃列表的话,已归档成员的真名不在映射表里 → 替换不掉 → 真名原样进 LLM prompt。
     * 这不是"名字显示不全"那种体感问题,是隐私红线({@code PrivacyIsolationTest} 守着)。
     */
    private final MemberDirectory memberDirectory;
    private final AccountMapper accountMapper;
    private final ProductCategoryService categoryService;
    private final FamilyService familyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // v0.3 FR-53d · 可选注入 · 无 goal 时 null safe(v0.2 行为不变)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.service.goal.GoalProgressService goalProgressService;

    /** 内存 cache:key = SHA-256(prompt context),TTL 1h */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 60L * 60 * 1000;

    /**
     * 全家维度综合诊断(默认走 cache · 1h TTL · 兼容老 caller)。
     */
    public DiagnoseResult diagnoseFamily(Long familyId, Long actorMemberId,
                                          FamilyDiagnose diagnose, List<Advice> adviceList) {
        return diagnoseFamily(familyId, actorMemberId, diagnose, adviceList, false);
    }

    /**
     * 全家维度综合诊断 · forceRefresh=true 跳过 cache 直接调 LLM 并覆写 cache。
     * 失败时返回 {@link DiagnoseResult#unavailable},前端展示降级占位。
     */
    public DiagnoseResult diagnoseFamily(Long familyId, Long actorMemberId,
                                          FamilyDiagnose diagnose, List<Advice> adviceList,
                                          boolean forceRefresh) {
        return diagnoseFamily(familyId, actorMemberId, diagnose, adviceList, forceRefresh,
                com.family.finance.service.analysis.AnalysisContext.baseline());
    }

    /**
     * v1.27 · 按「范围 + 模板 + 分析偏好」诊断(PRD FR-846)。
     *
     * <p>{@code diagnose} 必须是按 {@code ctx.scope()} 算出来的(调用方保证 —— 配置 / 风险两节与范围同源)。
     * 范围为空(全都标了)→ 不调用 AI(FR-826)。基线组合 → 提示词与 v1.26 逐字相同。</p>
     */
    public DiagnoseResult diagnoseFamily(Long familyId, Long actorMemberId,
                                          FamilyDiagnose diagnose, List<Advice> adviceList,
                                          boolean forceRefresh,
                                          com.family.finance.service.analysis.AnalysisContext ctx) {
        if (diagnose != null && diagnose.scopeEmpty()) {
            return DiagnoseResult.skipped("所有资产都标成了不参与配置分析,没有可分析的部分 —— AI 这次不分析。去账户页取消一个就能恢复。");
        }
        try {
            String familyName = familyService.require(familyId).getName();
            List<Member> members = memberDirectory.listAll(familyId);
            PromptBuilder.NameMapping mapping = PromptBuilder.buildNameMapping(members);
            // v1.28 FR-920 · 账户名换代号:只在系统写入的位置(账户清单 / 范围块),偏好原文不动
            AccountCodenames codes = AccountCodenames.of(accountMapper.findAllByFamily(familyId));

            // 组装 account summaries(已应用真名映射)· v1.27 只含范围内账户(FR-847)
            var dataScope = diagnose.scope() == null
                    ? com.family.finance.service.analysis.AnalysisScope.all() : diagnose.scope();
            List<PromptBuilder.AccountSummary> summaries = buildAccountSummaries(familyId, mapping, dataScope, codes);

            // v1.27 · 范围 / 模板 / 补充要求 / 分析偏好 —— 原文先过真名映射,放在全部材料之后
            String analysisBlocks = com.family.finance.service.analysis.AnalysisPromptBlocks.forAnalysis(
                    ctx, "「资产配置」「风险敞口」「各账户硬事实」", mapping.realToCodename(), codes::codeForName);

            // user prompt 不含真名 — applyMapping 已在上层处理
            String userPrompt = PromptBuilder.userPromptForFamily(
                    PromptBuilder.applyMapping(familyName, mapping.realToCodename()),
                    diagnose,
                    summaries,
                    applyMappingToAdvice(adviceList, mapping.realToCodename()),
                    mapping.realToCodename(),
                    analysisBlocks
            );

            // v0.3 FR-53d · 注入目标相对视角段(仅当家庭已设定目标时 · 无目标家庭行为完全保留 v0.2)
            String goalSection = buildGoalSection(familyId);
            if (goalSection != null && !goalSection.isBlank()) {
                userPrompt = userPrompt + "\n\n" + goalSection;
            }

            // v1.2 FR-8 · 注入本期再平衡计划执行率(闭环:AI 看到建议被执行了多少,只解读不生成新指令)
            try {
                if (rebalancePlanService != null) {
                    var pv = rebalancePlanService.activePlan(familyId);
                    if (pv != null && pv.total() > 0) {
                        userPrompt = userPrompt + "\n\n[再平衡计划执行情况(系统事实,仅解读)] 本期计划共 "
                                + pv.total() + " 条,已执行 " + pv.done() + " 条;评估已执行动作对配置的改善,不要生成新的买卖指令。";
                    }
                }
            } catch (Exception ignored) { /* 计划注入失败不影响诊断 */ }

            // v1.27 FR-872 · 范围内没有房产 → 不说「4 桶」;有房产 → 与 v1.26 同一份
            String systemPrompt = PromptBuilder.systemPromptForDiagnose(PromptBuilder.hasProperty(diagnose));

            // 防御深度:确保 prompt 里没有任何真名(否则就是 buildXxx 漏了字段)
            for (String real : mapping.realToCodename().keySet()) {
                if (real != null && real.length() >= 2 && userPrompt.contains(real)) {
                    log.error("LLM prompt 含真名 [{}],已 abort 调用以保护隐私", real);
                    return DiagnoseResult.unavailable("内部脱敏失败,已保护隐私");
                }
            }

            Map<String, String> legend = legend(mapping, codes);
            return runDiagnose(familyId, actorMemberId, "FAMILY", null,
                    systemPrompt, userPrompt, legend,
                    mapping.realToCodename().keySet(), forceRefresh,
                    PromptTrace.of(PromptSurface.DIAGNOSE_FAMILY).legend(legend)
                            .settings(ctx == null ? null : ctx.settingsNote()));
        } catch (Exception e) {
            log.warn("全家综合诊断失败 familyId={}: {}", familyId, e.getMessage());
            return DiagnoseResult.unavailable("诊断准备失败,请稍后重试。");
        }
    }

    /**
     * 账户维度综合诊断(默认走 cache · 兼容老 caller)。
     */
    public DiagnoseResult diagnoseAccount(Long familyId, Long actorMemberId,
                                           FamilyDiagnose familyDiagnose,
                                           AccountDiagnose accountDiagnose,
                                           List<Advice> adviceList) {
        return diagnoseAccount(familyId, actorMemberId, familyDiagnose, accountDiagnose, adviceList, false);
    }

    /**
     * 账户维度综合诊断 · forceRefresh=true 跳过 cache。
     */
    public DiagnoseResult diagnoseAccount(Long familyId, Long actorMemberId,
                                           FamilyDiagnose familyDiagnose,
                                           AccountDiagnose accountDiagnose,
                                           List<Advice> adviceList,
                                           boolean forceRefresh) {
        return diagnoseAccount(familyId, actorMemberId, familyDiagnose, accountDiagnose, adviceList, forceRefresh, List.of());
    }

    /** v1.27 · 单账户诊断也读分析偏好(FR-851)· {@code preferences} 是启用中的原文 */
    public DiagnoseResult diagnoseAccount(Long familyId, Long actorMemberId,
                                           FamilyDiagnose familyDiagnose,
                                           AccountDiagnose accountDiagnose,
                                           List<Advice> adviceList,
                                           boolean forceRefresh,
                                           List<String> preferences) {
        try {
            String familyName = familyService.require(familyId).getName();
            List<Member> members = memberDirectory.listAll(familyId);
            PromptBuilder.NameMapping mapping = PromptBuilder.buildNameMapping(members);

            // 此账户主理人代号
            String ownerCode = null;
            Long ownerId = accountDiagnose.account().getPrimaryOwnerMemberId();
            if (ownerId != null) {
                Member owner = members.stream().filter(m -> m.getId().equals(ownerId)).findFirst().orElse(null);
                if (owner != null && owner.getDisplayName() != null) {
                    ownerCode = mapping.realToCodename().get(owner.getDisplayName());
                }
            }

            AccountCodenames codes = AccountCodenames.of(accountMapper.findAllByFamily(familyId));
            // 给 LlmClient 看的 advice 文本是已应用真名映射的;原 advice 不动
            String userPrompt = PromptBuilder.userPromptForAccount(
                    PromptBuilder.applyMapping(familyName, mapping.realToCodename()),
                    familyDiagnose,
                    accountDiagnose,
                    applyMappingToAdvice(adviceList, mapping.realToCodename()),
                    mapping.realToCodename(),
                    ownerCode,
                    com.family.finance.service.analysis.AnalysisPromptBlocks.preferencesOnly(
                            preferences, mapping.realToCodename()),
                    codes.code(accountDiagnose.account().getId(), null)
            );
            String systemPrompt = PromptBuilder.systemPromptForDiagnose();

            // 防御深度:确保 prompt 里没有任何真名
            for (String real : mapping.realToCodename().keySet()) {
                if (real != null && real.length() >= 2 && userPrompt.contains(real)) {
                    log.error("LLM prompt 含真名 [{}],已 abort 调用以保护隐私", real);
                    return DiagnoseResult.unavailable("内部脱敏失败,已保护隐私");
                }
            }

            Map<String, String> legend = legend(mapping, codes);
            return runDiagnose(familyId, actorMemberId, "ACCOUNT",
                    accountDiagnose.account().getId(),
                    systemPrompt, userPrompt, legend,
                    mapping.realToCodename().keySet(), forceRefresh,
                    PromptTrace.of(PromptSurface.DIAGNOSE_ACCOUNT).legend(legend)
                            .settings(preferences == null || preferences.isEmpty() ? null : "分析偏好 " + preferences.size() + " 条"));
        } catch (Exception e) {
            log.warn("账户综合诊断失败 familyId={} accountId={}: {}",
                    familyId, accountDiagnose.account().getId(), e.getMessage());
            return DiagnoseResult.unavailable("诊断准备失败,请稍后重试。");
        }
    }

    /**
     * v0.6 FR-109 · AI 资产洞察综合诊断(集中度/资产负债表/再平衡·行为/低利率)。
     *
     * <p>硬数据已由 {@link com.family.finance.service.insight.AssetInsightService} 预算好;
     * 此处只把它铺成 prompt 让 LLM 中立解读。prompt <b>不含任何人名/账户名</b>
     * (见 {@link com.family.finance.service.insight.InsightPromptBuilder}),故 realNames 传空集;
     * 校验走更严的 {@link OutputValidator#checkInsight}(额外禁预测涨跌/择时)。</p>
     *
     * <p>洞察硬数据不可用时直接返回 {@link DiagnoseResult#unavailable},不调用 LLM。</p>
     */
    public DiagnoseResult diagnoseAssetInsight(Long familyId, Long actorMemberId,
                                               com.family.finance.service.insight.AssetInsight insight,
                                               boolean forceRefresh) {
        return diagnoseAssetInsight(familyId, actorMemberId, insight, forceRefresh,
                com.family.finance.service.analysis.AnalysisContext.baseline());
    }

    /**
     * v1.27 · 洞察按「范围 + 模板 + 分析偏好」(FR-846)。范围只作用在集中度 / 再平衡 / 低利率三维
     * ({@code insight} 已按 {@code ctx.scope()} 算好);家里写的原文先过真名映射。
     */
    public DiagnoseResult diagnoseAssetInsight(Long familyId, Long actorMemberId,
                                               com.family.finance.service.insight.AssetInsight insight,
                                               boolean forceRefresh,
                                               com.family.finance.service.analysis.AnalysisContext ctx) {
        try {
            if (insight == null || !insight.available()) {
                return DiagnoseResult.unavailable(
                        insight == null ? "洞察数据缺失" : insight.degradeReason());
            }
            // 家里写的原文(补充要求 / 偏好)可能提到成员 → 同一层真名映射;输出里的代号再反映射回来
            boolean hasFamilyText = ctx != null && (!ctx.preferences().isEmpty()
                    || (ctx.template() != null && ctx.template().extra() != null));
            PromptBuilder.NameMapping mapping = hasFamilyText
                    ? PromptBuilder.buildNameMapping(memberDirectory.listAll(familyId))
                    : new PromptBuilder.NameMapping(java.util.Map.of(), java.util.Map.of());
            String blocks = com.family.finance.service.analysis.AnalysisPromptBlocks.forAnalysis(
                    ctx, "「集中度」「再平衡偏离」「低利率·资产荒」", mapping.realToCodename());
            String systemPrompt = com.family.finance.service.insight.InsightPromptBuilder.systemPrompt(
                    insight.propertyInScope(), insight.hasLoans());
            String userPrompt = com.family.finance.service.insight.InsightPromptBuilder.userPrompt(insight, blocks);
            return runDiagnose(familyId, actorMemberId, "ASSET_INSIGHT", null,
                    systemPrompt, userPrompt, mapping.codenameToReal(),
                    java.util.Set.of(), forceRefresh,
                    PromptTrace.of(PromptSurface.ASSET_INSIGHT).legend(mapping.codenameToReal())
                            .settings(ctx == null ? null : ctx.settingsNote()));
        } catch (Exception e) {
            log.warn("资产洞察综合诊断失败 familyId={}: {}", familyId, e.getMessage());
            return DiagnoseResult.unavailable("洞察准备失败,请稍后重试。");
        }
    }

    private DiagnoseResult runDiagnose(Long familyId, Long actorMemberId,
                                        String scope, Long entityId,
                                        String systemPrompt, String userPrompt,
                                        Map<String, String> codenameToReal,
                                        java.util.Set<String> realNames,
                                        boolean forceRefresh,
                                        PromptTrace trace) {
        // v1.28 FR-921 · 不再把提示词正文打进日志(原来 DEBUG 级别会打整段);要看内容在页面上点 >_
        // 1. 查 cache(forceRefresh 跳过)
        // v1.27 · 键里带上系统提示词:范围内有没有房产 / 有没有贷款会换系统提示词(FR-872),
        //   模板 / 范围 / 偏好都在用户提示词里 —— 两者都进键,改了哪一样都不复用旧结论(FR-848)
        String cacheKey = sha256(scope + "|" + entityId + "|" + sha256(systemPrompt) + "|" + userPrompt);
        if (!forceRefresh) {
            CacheEntry hit = cache.get(cacheKey);
            if (hit != null && System.currentTimeMillis() - hit.timestamp < TTL_MS) {
                // cache 仍存 raw 字符串 · 渲染时再次解析 JSON(off-cache structured 重新建)
                DiagnoseStructured s = tryParseStructured(hit.diagnoseText);
                return DiagnoseResult.ok(hit.diagnoseText, hit.vendor, true, s).withRecord(hit.recordId());
            }
        } else {
            log.info("LLM diagnose forceRefresh · scope={} entityId={}", scope, entityId);
            cache.remove(cacheKey);
        }

        // 2. 走路由:主选 → 备选,顺序由 /admin/ai-access 「大模型」那一节的三级配置决定(v1.13)。
        //    这里是全项目最挑剔的一个调用方 —— 每次尝试无论成败都要进审计,输出还要过合规校验、
        //    没过就换下一家。所以用 Handler 形态:路由管「调谁、调不通换谁」,这里管「收不收」。
        DiagnoseResult routed = llmRouter.invoke(familyId, trace, systemPrompt, userPrompt,
                new LlmRouter.Handler<DiagnoseResult>() {
                    @Override
                    public DiagnoseResult onOutput(LlmInvocation inv, String raw, long elapsed) {
                        String badge = inv.badge();
                        // 校验(JSON 模式 · 把 JSON 里的所有 string 拼起来过 validator)
                        //   v0.4.9:LLM 输出 JSON · 把 user-facing 字段(narrative/finding/evidence/actions)
                        //   join 后过 OutputValidator,行为等价于老的纯文本校验
                        String textForValidate = joinUserFacingStrings(raw);
                        // v0.6 · 资产洞察走更严的合规校验(额外禁预测涨跌/择时);其余路径行为不变
                        OutputValidator.Result vr = "ASSET_INSIGHT".equals(scope)
                                ? OutputValidator.checkInsight(textForValidate, realNames)
                                : OutputValidator.check(textForValidate, realNames);
                        // 全交互日志(prompt + response + elapsed,无论接受与否都记)
                        LlmAuditLogger.log(badge, scope, familyId, entityId,
                                systemPrompt, userPrompt, raw, elapsed,
                                vr.accepted(), vr.accepted() ? null : vr.reason(), null);

                        if (!vr.accepted()) {
                            trace.rejected(vr.reason());
                            log.warn("LLM[{}] 综合诊断输出未通过校验: {}", badge, vr.reason());
                            auditLogService.record(familyId, actorMemberId, AuditLogType.LLM_REJECTED,
                                    "checkup_diagnose", entityId,
                                    "vendor=" + badge + " reason=" + vr.reason());
                            return null;      // 不接受 → 路由自动试下一个候选
                        }
                        try {
                            // 反映射代号 → 真名(给前端用户展示)
                            String mapped = PromptBuilder.reverseMapping(raw, codenameToReal);
                            cache.put(cacheKey, new CacheEntry(mapped, badge, System.currentTimeMillis(), trace));
                            DiagnoseStructured structured = tryParseStructured(mapped);
                            return DiagnoseResult.ok(mapped, badge, false, structured);
                        } catch (Exception e) {
                            log.warn("LLM[{}] 反映射/解析失败: {}", badge, e.getMessage());
                            return null;
                        }
                    }

                    @Override
                    public void onFailure(LlmInvocation inv, Exception e, long elapsed) {
                        String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        log.warn("LLM[{}] 调用失败: {} (elapsed={}ms)", inv.badge(), error, elapsed);
                        LlmAuditLogger.log(inv.badge(), scope, familyId, entityId,
                                systemPrompt, userPrompt, null, elapsed, false, null, error);
                    }
                });
        if (routed != null) return routed.withRecord(trace.recordId());
        if (llmRouter.testMode()) return DiagnoseResult.unavailable(trace.failureMessage()).withRecord(trace.recordId());

        // 3. 全部失败
        try {
            auditLogService.record(familyId, actorMemberId, AuditLogType.LLM_DEGRADED,
                    "checkup_diagnose", entityId,
                    "全部 LLM client 失败/无可用,综合诊断显示降级占位");
        } catch (Exception ignore) {
            // audit 失败不阻塞主流程
        }
        return DiagnoseResult.unavailable(trace.failureMessage()).withRecord(trace.recordId());
    }

    /** v1.28 · 代号 → 真名:成员 + 账户(反映射与面板对照共用一份;长的代号先换,「账户AB」不会被当成「账户A」) */
    private static Map<String, String> legend(PromptBuilder.NameMapping mapping, AccountCodenames codes) {
        Map<String, String> m = new java.util.LinkedHashMap<>(mapping.codenameToReal());
        m.putAll(codes.codeToReal());
        return m;
    }

    /**
     * v0.4.9 · 尝试把 LLM 输出 raw 字符串解析成结构化对象 · 解析失败返 null(前端会 fallback 显示 text)。
     * 支持 LLM 用 markdown 包裹的 JSON(```json ... ```)· 自动剥壳。
     */
    private DiagnoseStructured tryParseStructured(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            // 1. 提取首个 JSON 对象(去 markdown ``` 包裹)
            String clean = extractJsonObject(raw);
            if (clean == null) return null;
            JsonNode root = objectMapper.readTree(clean);

            // 2. overall
            JsonNode overallNode = root.path("overall");
            if (overallNode.isMissingNode() || overallNode.isNull()) return null;
            String overallVerdict = overallNode.path("verdict").asText("STABLE");
            String overallSummary = overallNode.path("summary").asText("");

            // 3. dimensions(应有 4 条 · 但容忍 LLM 偶尔少给 1 条)
            JsonNode dimsNode = root.path("dimensions");
            if (!dimsNode.isArray() || dimsNode.isEmpty()) return null;
            List<DiagnoseStructured.Dimension> dims = new ArrayList<>();
            // 维度名 → 默认图标(若 LLM 没给)
            Map<String, String> defaultIcons = Map.of(
                "资产配置", "📊", "风险敞口", "⚡", "流动性", "💧", "收益质量", "📈"
            );
            for (JsonNode d : dimsNode) {
                String name = d.path("name").asText("");
                if (name.isBlank()) continue;
                String icon = d.path("icon").asText(defaultIcons.getOrDefault(name, "•"));
                String verdict = d.path("verdict").asText("OK");
                String finding = d.path("finding").asText("");
                String evidence = d.path("evidence").asText("");
                dims.add(new DiagnoseStructured.Dimension(name, icon, verdict, finding, evidence));
            }
            if (dims.isEmpty()) return null;

            // 4. actions(可空)
            List<String> actions = new ArrayList<>();
            JsonNode actNode = root.path("actions");
            if (actNode.isArray()) {
                for (JsonNode a : actNode) {
                    String s = a.asText("");
                    if (!s.isBlank()) actions.add(s);
                }
            }

            return new DiagnoseStructured(overallVerdict, overallSummary, dims, actions);
        } catch (Exception e) {
            log.debug("LLM 输出非结构化 JSON · fallback 到纯文本: {}", e.getMessage());
            return null;
        }
    }

    /** 提取 raw 中第一个完整的 JSON 对象 · 去 markdown 包裹 */
    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        // 找首个 { 和最后一个 }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return raw.substring(start, end + 1);
    }

    /**
     * v0.4.9 · 把 JSON 输出里所有 user-facing string 拼起来给 OutputValidator 扫描。
     * 解析失败 → 直接返 raw(老路径行为)。
     */
    private String joinUserFacingStrings(String raw) {
        if (raw == null) return "";
        try {
            String clean = extractJsonObject(raw);
            if (clean == null) return raw;
            JsonNode root = objectMapper.readTree(clean);
            StringBuilder sb = new StringBuilder();
            // overall.summary
            sb.append(root.path("overall").path("summary").asText("")).append("\n");
            // dimensions[].finding + evidence
            for (JsonNode d : root.path("dimensions")) {
                sb.append(d.path("finding").asText("")).append("\n");
                sb.append(d.path("evidence").asText("")).append("\n");
            }
            // actions[]
            for (JsonNode a : root.path("actions")) {
                sb.append(a.asText("")).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception ignored) {
            return raw;
        }
    }

    private List<PromptBuilder.AccountSummary> buildAccountSummaries(Long familyId,
                                                                     PromptBuilder.NameMapping mapping,
                                                                     com.family.finance.service.analysis.AnalysisScope scope,
                                                                     AccountCodenames codes) {
        List<Account> accounts = accountMapper.findActiveByFamily(familyId).stream()
                .filter(a -> scope == null || scope.includes(a.getId()))
                .toList();
        List<Member> members = memberDirectory.listAll(familyId);
        List<PromptBuilder.AccountSummary> out = new ArrayList<>();
        for (Account a : accounts) {
            ProductCategory cat = a.getProductCategoryCode() == null ? null
                    : categoryService.findByCode(a.getProductCategoryCode()).orElse(null);
            String riskLabel;
            int level = a.getRiskLevelOverride() != null
                    ? a.getRiskLevelOverride()
                    : (cat != null ? cat.getRiskLevel() : 0);
            riskLabel = "★".repeat(Math.max(0, Math.min(level, 6))) + (level > 0 ? "" : "—");

            String ownerCode = null;
            if (a.getPrimaryOwnerMemberId() != null) {
                Member m = members.stream().filter(x -> x.getId().equals(a.getPrimaryOwnerMemberId()))
                        .findFirst().orElse(null);
                if (m != null && m.getDisplayName() != null) {
                    ownerCode = mapping.realToCodename().get(m.getDisplayName());
                }
            }

            // 此处不能调用 AccountDiagnoseService(会循环依赖 + 太重),只给基础硬事实
            // 完整 AccountDiagnose 在账户维度 prompt 中才传入
            out.add(new PromptBuilder.AccountSummary(
                    codes.code(a.getId(), PromptBuilder.applyMapping(a.getDisplayName(), mapping.realToCodename())),
                    a.getType().name(),
                    a.getProductCategoryCode(),
                    riskLabel,
                    ownerCode,
                    null,  // currentBalance:全家维度时不传单账户余额(规则文本里已含)
                    null,
                    cat == null ? null : cat.getBenchmarkLabel(),
                    cat == null ? null : (cat.getBenchmarkPct() == null ? null
                            : cat.getBenchmarkPct().multiply(new BigDecimal("100")))
            ));
        }
        return out;
    }

    /** 把 advice 列表里的 rawTitle/rawBody 应用真名映射(防御深度) */
    private List<Advice> applyMappingToAdvice(List<Advice> list, Map<String, String> realToCodename) {
        if (realToCodename.isEmpty()) return list;
        List<Advice> out = new ArrayList<>(list.size());
        for (Advice a : list) {
            out.add(new Advice(
                    a.ruleId(), a.scope(), a.accountId(), a.dimension(), a.severity(), a.category(),
                    PromptBuilder.applyMapping(a.rawTitle(), realToCodename),
                    PromptBuilder.applyMapping(a.rawBody(), realToCodename),
                    PromptBuilder.applyMapping(a.title(), realToCodename),
                    PromptBuilder.applyMapping(a.body(), realToCodename),
                    a.cta()
            ));
        }
        return out;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * v1.28 · 记录编号在调用循环结束后才有(路由写回 trace),所以存 trace 本身:命中缓存时读它的编号。
     */
    private record CacheEntry(String diagnoseText, String vendor, long timestamp, PromptTrace trace) {
        Long recordId() { return trace == null ? null : trace.recordId(); }
    }

    /**
     * AI 综合诊断结果。
     *
     * @param available  AI 是否可用(false 时 text 为占位文案)
     * @param text       AI 输出综合诊断长文(已反映射回真名);或降级占位
     * @param vendor     成功时:实际 LLM 厂商(qwen / deepseek);降级时为 "fallback"
     * @param fromCache  是否来自缓存
     * @param generatedAt 生成时刻(展示用)
     * @param structured v0.4.9 起 · LLM 输出结构化 JSON 时填入 · 老路径 / fallback 时为 null
     * @param truncated  v0.4.10 起 · text 看起来是被截断的 JSON 时为 true · 前端显示"输出截断 请刷新"
     */
    public record DiagnoseResult(
            boolean available,
            String text,
            String vendor,
            boolean fromCache,
            Instant generatedAt,
            DiagnoseStructured structured,
            boolean truncated,
            Long promptRecordId
    ) {
        /** v1.28 · 七参老构造(不带记录编号) */
        public DiagnoseResult(boolean available, String text, String vendor, boolean fromCache,
                              Instant generatedAt, DiagnoseStructured structured, boolean truncated) {
            this(available, text, vendor, fromCache, generatedAt, structured, truncated, null);
        }

        /** v1.28 · 带上「这份结果是哪一次调用生成的」(PRD FR-909) */
        public DiagnoseResult withRecord(Long id) {
            return new DiagnoseResult(available, text, vendor, fromCache, generatedAt, structured, truncated, id);
        }

        public static DiagnoseResult ok(String text, String vendor, boolean fromCache) {
            return new DiagnoseResult(true, text, vendor, fromCache, Instant.now(), null, false);
        }
        public static DiagnoseResult ok(String text, String vendor, boolean fromCache, DiagnoseStructured structured) {
            return new DiagnoseResult(true, text, vendor, fromCache, Instant.now(), structured,
                    structured == null && looksTruncatedJson(text));
        }
        /**
         * v1.27 · 有意不调用(范围为空等)—— 与「AI 挂了」区分开,给用户看的是原因,不是「稍后刷新重试」。
         */
        public static DiagnoseResult skipped(String humanText) {
            return new DiagnoseResult(false, humanText, "skipped", false, Instant.now(), null, false);
        }

        public static DiagnoseResult unavailable(String reason) {
            return new DiagnoseResult(false,
                    (reason == null || reason.isBlank() ? "AI 综合诊断暂时不可用。" : reason)
                            + "\n以上系统规则与数字仍然可用。",
                    "fallback", false, Instant.now(), null, false);
        }

        /** 启发式判断:raw 以 { 开头但未正确闭合(无 } 结尾)→ 被截断的 JSON */
        private static boolean looksTruncatedJson(String text) {
            if (text == null) return false;
            String t = text.trim();
            return t.startsWith("{") && !t.endsWith("}");
        }
    }

    /**
     * v0.4.9 · AI 诊断结构化输出(JSON 解析后)
     * <p>前端按 overall 总评 + 4 dimension 卡 + actions 列表渲染 · 替代纯文本散文。
     *
     * @param overallVerdict 总体判断 STABLE | NEEDS_ATTENTION | RISK
     * @param overallSummary 1-2 句总评(40-80 字)
     * @param dimensions     4 个诊断维度卡(配置/风险/流动性/收益)
     * @param actions        1-3 条优先行动(可执行 · 跨规则综合)
     */
    public record DiagnoseStructured(
            String overallVerdict,
            String overallSummary,
            List<Dimension> dimensions,
            List<String> actions
    ) {
        /**
         * 单个诊断维度卡。
         *
         * @param name     维度名(资产配置 / 风险敞口 / 流动性 / 收益质量)
         * @param icon     单字符 emoji(📊 / ⚡ / 💧 / 📈)
         * @param verdict  OK | WARN | RISK
         * @param finding  诊断结论(30-80 字)
         * @param evidence 数据支撑(20-50 字 · 引用上下文硬事实)
         */
        public record Dimension(
                String name,
                String icon,
                String verdict,
                String finding,
                String evidence
        ) {}
    }

    /** 透出 cache 状态(供测试用) */
    public Optional<String> peekCache(String key) {
        CacheEntry e = cache.get(key);
        return Optional.ofNullable(e == null ? null : e.diagnoseText);
    }

    /**
     * v0.3 FR-53d · 构建目标相对视角段 · 无目标时返回 null(prompt 不加段)。
     */
    private String buildGoalSection(Long familyId) {
        if (goalProgressService == null || familyId == null) return null;
        try {
            var progresses = goalProgressService.computeAll(familyId);
            if (progresses.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("家庭已设定的目标(基于目标的相对视角):\n");
            for (var p : progresses) {
                if (p.isNewRetirement()) {
                    sb.append("  - ").append(p.retirement().result().aiSummary()).append('\n');
                    continue;
                }
                String name = p.goal().getName();
                String type = p.goal().getGoalType().name();
                int pct = p.progressPct().intValue();
                String dateLabel = p.neutralDate() == null ? "未达成范围"
                    : p.neutralDate().getYear() + "(中性 5%)";
                sb.append("  - ").append(name)
                  .append("(").append(type).append(")· 进度 ").append(pct).append("% · 预计达成 ")
                  .append(dateLabel).append("\n");
            }
            sb.append("\n请评估当前资产配置是否贴合上述时间表,在综合诊断中体现「距 N 年」「股票/现金占比是否合理」等长期视角。");
            return sb.toString();
        } catch (Exception e) {
            log.warn("buildGoalSection failed (non-blocking): {}", e.toString());
            return null;
        }
    }
}
