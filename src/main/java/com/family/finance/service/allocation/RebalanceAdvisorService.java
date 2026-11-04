package com.family.finance.service.allocation;

import com.family.finance.service.llmtrace.AccountCodenames;
import com.family.finance.service.llmtrace.PromptRecorder;
import com.family.finance.service.llmtrace.PromptSurface;
import com.family.finance.service.llmtrace.PromptTrace;

import com.family.finance.calc.AllocationDiff.Bucket;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.allocation.RebalanceAdviceCache;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.member.Member;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.member.MemberDirectory;
import com.family.finance.repository.RebalanceAdviceCacheMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.checkup.llm.LlmRouter;
import com.family.finance.service.checkup.llm.OutputValidator;
import com.family.finance.service.checkup.llm.PromptBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * v0.4 FR-62b · AI 调仓建议服务。
 *
 * <p>复用 v0.2/0.3 LLM 接入 + PromptBuilder + OutputValidator;v1.13 起主备编排统一走 {@link LlmRouter}。</p>
 *
 * <p>节流:30 天 TTL · 同 family + anchor 30 天内返缓存。</p>
 *
 * <p>输出 JSON schema:</p>
 * <pre>{
 *   "narrative": "string · 1-3 句叙事",
 *   "actions": [{
 *     "from_account": "string · 必须是真实账户名",
 *     "to_account":   "string",
 *     "amount":       number,
 *     "reason":       "string · 简短"
 *   }]
 * }</pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RebalanceAdvisorService {

    private static final int CACHE_TTL_DAYS = 30;
    /** 单条 action 金额不允许超过该账户余额的此比例(防 LLM 给极端值) */
    private static final BigDecimal MAX_AMOUNT_RATIO = new BigDecimal("0.50");

    private final LlmRouter llmRouter;
    private final FamilyService familyService;
    /**
     * v1.15 FR-382 · 脱敏映射必须**含已归档成员**。
     * 只拿活跃列表的话,已归档成员的真名不在映射表里 → 替换不掉 → 真名原样进 LLM prompt。
     * 这不是"名字显示不全"那种体感问题,是隐私红线({@code PrivacyIsolationTest} 守着)。
     */
    private final MemberDirectory memberDirectory;
    private final AccountMapper accountMapper;
    private final FactViewService factViewService;
    private final AllocationService allocationService;
    private final RebalanceAdviceCacheMapper cacheMapper;
    /** v1.28 · 记下发出去的内容(PRD FR-909)· 字段注入,单测的八参构造不用管它 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PromptRecorder promptRecorder;
    private final ObjectMapper objectMapper;

    /**
     * 主入口:获取调仓建议(命中 30 天缓存直接返,否则调 LLM)。
     * 用户点页面「↻ 刷新」按钮 → forceRefresh=true 跳过缓存直接调 LLM 并覆写缓存。
     *
     * @return AdviceResult.unavailable 表示 LLM 全部失败;否则带 actions + narrative
     */
    public AdviceResult advise(long familyId) {
        return advise(familyId, false);
    }

    public AdviceResult advise(long familyId, boolean forceRefresh) {
        return advise(familyId, forceRefresh, com.family.finance.service.analysis.AnalysisContext.baseline());
    }

    /**
     * v1.27 · 按「范围 + 模板 + 分析偏好」给建议(PRD FR-846 / FR-827)。
     *
     * <p>缓存键(FR-848 · tech-design v1.27 选型七):锚码,或「锚码|16 位指纹」—— 指纹含范围与标记集合、
     * 模板与版本、偏好、自定义锚的四个值。基线组合仍是纯锚码,老缓存照常命中。</p>
     */
    public AdviceResult advise(long familyId, boolean forceRefresh,
                               com.family.finance.service.analysis.AnalysisContext ctx) {
        try {
            Family f = familyService.require(familyId);
            com.family.finance.service.analysis.AnalysisContext c =
                    ctx == null ? com.family.finance.service.analysis.AnalysisContext.baseline() : ctx;
            if (c.scope().empty()) {
                return AdviceResult.unavailable("所有资产都标成了不参与配置分析,没有可分析的部分");
            }
            String anchor = cacheKey(f, c);

            // 1. 查缓存(30 天 TTL · forceRefresh 跳过)
            if (!forceRefresh) {
                Optional<RebalanceAdviceCache> cached = cacheMapper.findByFamilyAndAnchor(familyId, anchor);
                if (cached.isPresent()) {
                    long days = Duration.between(cached.get().getGeneratedAt(), LocalDateTime.now()).toDays();
                    if (days <= CACHE_TTL_DAYS) {
                        log.info("rebalance advice cache hit · family={} anchor={} age={}d", familyId, anchor, days);
                        return parseFromJson(cached.get().getContentJson(), cached.get().getGeneratedAt(), true)
                                .withRecord(cached.get().getPromptRecordId());
                    }
                }
            } else {
                log.info("rebalance advice forceRefresh · family={} anchor={}", familyId, anchor);
            }

            // 2. 准备 prompt 上下文 · v1.27 配置只看范围内、目标按有效目标(FR-870)
            FactSlice slice = factViewService.loadDefault(familyId);
            AllocationService.DiffResult diff = allocationService.compute(familyId, slice, c.scope(), c.anchorOverride());
            if (diff.customUnset()) return AdviceResult.unavailable("还没填自定义配置锚的目标 —— 先去填,再让 AI 给步骤");
            if (!diff.comparable()) return AdviceResult.unavailable("当前配置没法和这个锚对照(目标在你家有的几类上全是 0)");
            // 只把范围内账户交给 AI:被标「不参与配置分析」的不许出现在调仓步骤里(FR-847)
            List<Account> accounts = accountMapper.findActiveByFamily(familyId).stream()
                    .filter(a -> c.scope().includes(a.getId()))
                    .toList();
            List<Member> members = memberDirectory.listAll(familyId);
            PromptBuilder.NameMapping mapping = PromptBuilder.buildNameMapping(members);
            // v1.28 FR-920 · 账户清单写代号;AI 回答里的代号在校验与解析之前换回真名
            AccountCodenames codes = AccountCodenames.of(accountMapper.findAllByFamily(familyId));
            Map<String, String> legend = new java.util.LinkedHashMap<>(mapping.codenameToReal());
            legend.putAll(codes.codeToReal());

            String system = """
                你是家庭资产配置顾问 · 严格按以下规则输出:
                1. 只输出 JSON 对象 · 不要 markdown 包裹 · 不要解释段
                2. JSON 必须含 narrative(1-3 句叙事)+ actions 数组(每条 from_account / to_account / amount / reason)
                3. from_account 和 to_account 必须原样使用给定账户列表里的账户名(已换成「账户A / 账户B」这类代号)
                4. amount 必须 ≤ from_account 余额 × 0.5(避免极端调仓)· 单位:本位币 元
                5. actions 不超过 4 条 · 优先级:最大偏离的桶
                6. 不要使用真名(成员代号已脱敏)· 不要使用具体产品代码 / 担保性词(保证 / 稳赚)
                """;
            // v1.27 FR-875 · 提示词本来就说给了各账户余额、并要求「金额 ≤ 余额 × 0.5」—— 这回真的带上
            Map<Long, BigDecimal> balances = new HashMap<>();
            for (var r : slice.rows()) {
                if (Objects.equals(r.periodId(), slice.lastPeriodId()) && r.accountId() != null) {
                    balances.put(r.accountId(), r.endBalanceBase() == null ? BigDecimal.ZERO : r.endBalanceBase());
                }
            }
            String user = buildPrompt(f, diff, accounts, members, mapping, balances, codes)
                    + blocksSuffix(c, mapping, codes);
            PromptTrace trace = PromptTrace.of(PromptSurface.REBALANCE).legend(legend).settings(c.settingsNote());
            String raw = llmRouter.invoke(familyId, trace, system, user).map(LlmRouter.Outcome::text).orElse(null);
            if (raw == null) return AdviceResult.unavailable(trace.failureMessage()).withRecord(trace.recordId());
            raw = PromptBuilder.reverseMapping(raw, legend);

            // 3. 校验 + 解析
            //    rebalance 这条路径的 prompt 不向 LLM 传成员信息(只传账户列表 + 4 桶配置)
            //    所以 LLM 物理上不可能输出"真名"· 真名扫描在这里 0 价值 100% 误杀风险
            //    → 传空 realNames 跳过第 4 层(v0.4.7 调整 · 解决 prod「萝卜」误杀)
            //    账户名白名单仍保留(防 PRODUCT_NAME_PATTERN 误杀「支付宝-余额宝」对自家账户的引用)
            java.util.Set<String> accountNameWhitelist = accounts.stream()
                .map(Account::getDisplayName)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            // 4. 先解析 JSON,再只拿「给人看的字」(叙事 + 每条理由)去过校验。
            //    v1.28 修:原来拿整段原始 JSON 去校验,"amount": 100000 这种 6 位金额会撞上「6 位 A 股代码」
            //    那条规则 → 整份建议被判「含具体产品名/代码」丢掉(beta 上 e2e 经 >_ 面板照出来的)。
            //    金额是数字字段,不是给人看的字;下面还有「≤ 余额 × 0.5」的上限兜着。
            String cleanedJson = extractJsonObject(raw);
            if (cleanedJson == null) {
                if (promptRecorder != null) promptRecorder.markRejected(familyId, trace.recordId(), "回答不是 JSON");
                return AdviceResult.unavailable("LLM 输出非 JSON").withRecord(trace.recordId());
            }
            JsonNode root = objectMapper.readTree(cleanedJson);
            StringBuilder userFacing = new StringBuilder(root.path("narrative").asText(""));
            if (root.path("actions").isArray()) {
                for (JsonNode a : root.path("actions")) userFacing.append('\n').append(a.path("reason").asText(""));
            }
            OutputValidator.Result valid = OutputValidator.check(
                userFacing.toString(), java.util.Set.of(), accountNameWhitelist);
            if (!valid.accepted()) {
                log.warn("rebalance advice LLM output 校验失败: {}", valid.reason());
                if (promptRecorder != null) promptRecorder.markRejected(familyId, trace.recordId(), valid.reason());
                return AdviceResult.unavailable("LLM 输出未通过校验:" + valid.reason()).withRecord(trace.recordId());
            }

            JsonNode actionsNode = root.path("actions");
            String narrative = root.path("narrative").asText("");

            // amount sanity: 删除超额 / 账户不存在的 actions
            Map<String, BigDecimal> balByName = new HashMap<>();
            for (Account a : accounts) {
                // 用 fact 末期余额查
                BigDecimal bal = slice.rows().stream()
                    .filter(r -> Objects.equals(r.accountId(), a.getId()))
                    .filter(r -> Objects.equals(r.periodId(), slice.lastPeriodId()))
                    .map(r -> r.endBalanceBase() == null ? BigDecimal.ZERO : r.endBalanceBase())
                    .findFirst().orElse(BigDecimal.ZERO);
                balByName.put(a.getDisplayName(), bal);
            }
            java.util.List<java.util.Map<String, Object>> sanitized = new java.util.ArrayList<>();
            if (actionsNode.isArray()) {
                for (JsonNode a : actionsNode) {
                    String fromAcc = a.path("from_account").asText(null);
                    String toAcc = a.path("to_account").asText(null);
                    double amt = a.path("amount").asDouble(0);
                    String reason = a.path("reason").asText("");
                    if (fromAcc == null || toAcc == null || amt <= 0) continue;
                    BigDecimal balance = balByName.get(fromAcc);
                    if (balance == null) continue; // 账户不存在
                    BigDecimal cap = balance.multiply(MAX_AMOUNT_RATIO);
                    if (BigDecimal.valueOf(amt).compareTo(cap) > 0) {
                        amt = cap.doubleValue(); // 截断到上限
                    }
                    Map<String, Object> safe = new java.util.LinkedHashMap<>();
                    safe.put("from_account", fromAcc);
                    safe.put("to_account", toAcc);
                    safe.put("amount", (long) amt);
                    safe.put("reason", reason);
                    sanitized.add(safe);
                }
            }

            String cleanContent = objectMapper.writeValueAsString(Map.of(
                "narrative", narrative,
                "actions", sanitized));

            // 5. 写缓存 · v1.28 旧的那份结果被覆盖,它的「发出去的内容」记录一起走(FR-915)
            Long oldRecord = cacheMapper.findByFamilyAndAnchor(familyId, anchor)
                    .map(RebalanceAdviceCache::getPromptRecordId).orElse(null);
            cacheMapper.upsert(RebalanceAdviceCache.builder()
                .familyId(familyId)
                .anchorCode(anchor)
                .contentJson(cleanContent)
                .promptRecordId(trace.recordId())
                .build());
            if (promptRecorder != null && oldRecord != null && !oldRecord.equals(trace.recordId())) {
                promptRecorder.forget(familyId, oldRecord);
            }

            return new AdviceResult(true, narrative, sanitized, LocalDateTime.now(), false, null, trace.recordId());
        } catch (Exception e) {
            log.warn("rebalance advise failed family={}: {}", familyId, e.toString());
            return AdviceResult.unavailable("调仓建议暂时无法生成,请稍后重试。");
        }
    }

    /** anchor 切换或用户主动重新生成 → 删缓存 */
    public void invalidate(long familyId) {
        cacheMapper.deleteByFamily(familyId);
    }

    // ---------- 内部 ----------


    /**
     * 缓存键:基线组合(全部资产 · 综合体检 · 没偏好 · 非自定义锚)= 纯锚码,与 v1.26 同一行;
     * 否则「锚码|16 位指纹」。{@code anchor_code} 列宽 32,「XQ_CONSERVATIVE|」+16 = 32,放得下。
     */
    public String cacheKey(Family f, com.family.finance.service.analysis.AnalysisContext c) {
        String anchor = c.anchorOverride() != null ? c.anchorOverride()
                : (f.getAllocationAnchor() == null ? "SP_4321" : f.getAllocationAnchor());
        boolean custom = "CUSTOM".equalsIgnoreCase(anchor);
        if (c.isBaseline() && !custom) return anchor;
        String fp = c.fingerprint() + "|" + (custom ? String.valueOf(f.getAllocationAnchorCustom()) : "");
        return anchor + "|" + sha16(fp);
    }

    /** 报表页渲染:这个组合下 30 天内的缓存建议(没有 → empty) */
    public Optional<AdviceResult> cached(long familyId, com.family.finance.service.analysis.AnalysisContext ctx) {
        Family f = familyService.require(familyId);
        return cacheMapper.findByFamilyAndAnchor(familyId, cacheKey(f, ctx == null
                        ? com.family.finance.service.analysis.AnalysisContext.baseline() : ctx))
                .filter(r -> Duration.between(r.getGeneratedAt(), LocalDateTime.now()).toDays() <= CACHE_TTL_DAYS)
                .map(r -> parseFromJson(r.getContentJson(), r.getGeneratedAt(), true).withRecord(r.getPromptRecordId()))
                .filter(AdviceResult::ok);
    }

    /** 范围 / 模板 / 补充要求 / 偏好段落(基线为空串 → 提示词不变) */
    private static String blocksSuffix(com.family.finance.service.analysis.AnalysisContext c,
                                       PromptBuilder.NameMapping mapping, AccountCodenames codes) {
        String b = com.family.finance.service.analysis.AnalysisPromptBlocks.forAnalysis(
                c, "「4 类目配置」「各账户当前余额」", mapping.realToCodename(), codes::codeForName);
        return b.isBlank() ? "" : "\n" + b + "\n";
    }

    private static String sha16(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            return String.format("%016x", (long) s.hashCode());
        }
    }

    private String buildPrompt(Family f, AllocationService.DiffResult diff,
                               List<Account> accounts, List<Member> members,
                               PromptBuilder.NameMapping mapping, Map<Long, BigDecimal> balances,
                               AccountCodenames codes) {
        StringBuilder sb = new StringBuilder();
        sb.append("家庭基础:\n");
        sb.append("- 风险偏好: ").append(f.getRiskAppetite()).append("\n");
        sb.append("- 本位币: ").append(f.getBaseCurrency()).append("\n");
        sb.append("- 当前选模板: ").append(diff.anchorCode()).append("\n\n");

        sb.append("4 类目配置(% · 当前 vs 目标 vs 偏离):\n");
        // v1.27 FR-870 · 只写参与对照的桶(范围内没有房产 / 保险的,那一桶不参与,目标已按比例放大)
        for (String key : diff.activeBuckets()) {
            Bucket b = Bucket.valueOf(key);
            sb.append("- ").append(bucketCn(b)).append(": ")
              .append(diff.currentPct().get(b.name())).append("% vs ")
              .append(diff.targetPct().get(b.name())).append("% (")
              .append(formatSigned(diff.diffPct().get(b.name()))).append("%)\n");
        }
        if (diff.rescaled() || !diff.droppedBuckets().isEmpty()) {
            sb.append("(").append(diff.droppedBuckets().stream().map(AllocationService.DiffResult::bucketCn)
                    .collect(java.util.stream.Collectors.joining("、")))
              .append(" 不参与对照:范围内没有这一类;其余目标已按原比例放大,系统已算好)\n");
        }
        if (diff.otherAmount() != null && diff.otherAmount().signum() > 0) {
            sb.append("(另有「其他」类资产 ¥").append(diff.otherAmount().setScale(0, java.math.RoundingMode.HALF_UP).toPlainString())
              .append(",如车 · 不参与四桶对照,不要把它调来调去)\n");
        }
        sb.append("\n各账户当前余额(本位币 · 优先按 product_category 已映射 4 桶):\n");
        for (Account a : accounts) {
            BigDecimal bal = balances == null ? null : balances.get(a.getId());
            sb.append("- ").append(codes.code(a.getId(), a.getDisplayName()))
              .append(" (").append(a.getType())
              .append(", 类目=").append(a.getProductCategoryCode() == null ? "未设" : a.getProductCategoryCode())
              .append(")")
              .append(bal == null ? "" : " 当前余额=¥" + bal.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString())
              .append("\n");
        }
        sb.append("\n请基于 4 桶偏离 + 上述账户列表,给出 2-4 个具体调仓 action · 输出严格 JSON。\n");
        return sb.toString();
    }

    private String bucketCn(Bucket b) {
        return switch (b) {
            case CASH -> "现金";
            case INVEST -> "投资";
            case PROPERTY -> "房产";
            case INSURANCE -> "保险";
        };
    }

    private String formatSigned(BigDecimal v) {
        if (v == null) return "—";
        if (v.signum() > 0) return "+" + v.toPlainString();
        return v.toPlainString();
    }

    /** LLM 输出可能被 markdown 包裹 · 提取首个 {...} 对象 */
    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        int s = raw.indexOf('{');
        int e = raw.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        return raw.substring(s, e + 1);
    }

    private AdviceResult parseFromJson(String json, LocalDateTime generatedAt, boolean fromCache) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String narrative = root.path("narrative").asText("");
            java.util.List<java.util.Map<String, Object>> actions = new java.util.ArrayList<>();
            JsonNode actionsNode = root.path("actions");
            if (actionsNode.isArray()) {
                for (JsonNode a : actionsNode) {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("from_account", a.path("from_account").asText(""));
                    m.put("to_account", a.path("to_account").asText(""));
                    m.put("amount", a.path("amount").asLong(0));
                    m.put("reason", a.path("reason").asText(""));
                    actions.add(m);
                }
            }
            return new AdviceResult(true, narrative, actions, generatedAt, fromCache, null);
        } catch (Exception e) {
            return AdviceResult.unavailable("缓存 JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 调仓建议结果。
     */
    public record AdviceResult(
        boolean ok,
        String narrative,
        java.util.List<java.util.Map<String, Object>> actions,
        LocalDateTime generatedAt,
        boolean fromCache,
        String errorReason,
        Long promptRecordId
    ) {
        /** v1.28 · 六参老构造(不带记录编号) */
        public AdviceResult(boolean ok, String narrative, java.util.List<java.util.Map<String, Object>> actions,
                            LocalDateTime generatedAt, boolean fromCache, String errorReason) {
            this(ok, narrative, actions, generatedAt, fromCache, errorReason, null);
        }

        /** v1.28 · 带上「这份建议是哪一次调用生成的」(PRD FR-909) */
        public AdviceResult withRecord(Long id) {
            return new AdviceResult(ok, narrative, actions, generatedAt, fromCache, errorReason, id);
        }

        public static AdviceResult unavailable(String reason) {
            return new AdviceResult(false, null, java.util.List.of(), null, false, reason);
        }
    }
}
