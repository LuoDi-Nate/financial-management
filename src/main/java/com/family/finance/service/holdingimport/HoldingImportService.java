package com.family.finance.service.holdingimport;

import com.family.finance.config.AppProperties;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.holdingimport.HoldingImport;
import com.family.finance.domain.holdingimport.HoldingImportItem;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.HoldingImportItemMapper;
import com.family.finance.repository.HoldingImportMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.lens.LensAiTagService;
import com.family.finance.service.stock.AccountValuationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * v1.4 · 持仓截图导入服务(状态机 job + 异步识别 + 三态匹配 + 确认落库)。
 *
 * <p>UPLOADING(收图/存压缩)→ SCANNING(@Async 视觉识别+合并去重+打标+三态匹配)→
 * REVIEW(待用户比对确认)→ CONFIRMED(落库+估值交接) / ABANDONED。</p>
 *
 * <p>只在同账户、{@code sync_source=SCREENSHOT} 的持仓间做增删改;手填/券商持仓永不被碰。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HoldingImportService {

    public static final String SYNC_SOURCE = "SCREENSHOT";

    private final HoldingImportMapper importMapper;
    private final HoldingImportItemMapper itemMapper;
    private final StockHoldingMapper holdingMapper;
    private final AccountMapper accountMapper;
    private final PeriodMapper periodMapper;
    private final VisionLlmClient vision;
    private final LensAiTagService tagService;
    private final AccountValuationService valuationService;
    private final AppProperties props;
    private final com.family.finance.service.penetration.FundPenetrationService penetrationService; // v1.5
    /** v1.30 · 命中净值行时按截图市值反推份额(不碰单价)—— 见 confirm 的 UPDATE 分支 */
    private final com.family.finance.service.fund.FundHoldingService fundHoldingService;

    // ---------- 状态机 ----------

    /** 进某账户导入页:有未完成(SCANNING/REVIEW)的则续看,否则新建 UPLOADING。 */
    @Transactional
    public HoldingImport startOrResume(long familyId, long accountId) {
        Optional<HoldingImport> open = importMapper.findOpenByAccount(familyId, accountId);
        if (open.isPresent()) return open.get();
        Account acc = accountMapper.findById(familyId, accountId).orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        Period period = periodMapper.findBalancePeriod(acc.getFamilyId())
                .orElseThrow(() -> new IllegalStateException("没有开账期,无法导入"));
        HoldingImport imp = HoldingImport.builder()
                .familyId(acc.getFamilyId()).accountId(accountId).periodId(period.getId())
                .status(HoldingImport.UPLOADING).visionModel(vision.model())
                .imgCount(0).build();
        importMapper.insert(imp);
        return imp;
    }

    public Optional<HoldingImport> get(long familyId, long importId) { return importMapper.findById(familyId, importId); }

    public List<HoldingImportItem> items(long familyId, long importId) { return itemMapper.findByImport(familyId, importId); }

    // ---------- 上传(压缩图已由前端做,服务端存 + 校验) ----------

    /** 存一张压缩图到 uploadRoot/family-{fid}/holdingshots/{importId}-{n}.{ext};返回相对路径。 */
    public String saveImage(HoldingImport imp, byte[] bytes, String contentType) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("空文件");
        if (bytes.length > 8 * 1024 * 1024) throw new IllegalArgumentException("单图超过 8MB");
        String ct = contentType == null ? "" : contentType;
        if (!ct.startsWith("image/")) throw new IllegalArgumentException("仅支持图片");
        String ext = ct.contains("png") ? "png" : ct.contains("webp") ? "webp" : "jpg";
        Path root = Paths.get(props.uploadRoot()).toAbsolutePath().normalize();
        Path dir = root.resolve("family-" + imp.getFamilyId()).resolve("holdingshots");
        Files.createDirectories(dir);
        // v1.4.2 · 文件序号取"现存最大 +1"(不再用 imgCount+1)· 删中间张后下次上传不会覆盖已有图
        int n = nextImageIndex(imp);
        String rel = "family-" + imp.getFamilyId() + "/holdingshots/" + imp.getId() + "-" + n + "." + ext;
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) throw new IllegalStateException("非法路径");
        Files.write(target, bytes);
        int count = listImages(imp).size();   // imgCount = 实际文件数(含刚写的),与删除后计数自洽
        importMapper.updateImgCount(imp.getFamilyId(), imp.getId(), count);
        imp.setImgCount(count);
        return rel;
    }

    /** v1.4.2 · 下一个图片序号 = 现存 {importId}-{n} 的最大 n + 1(删除后不复用旧号,防覆盖)。 */
    private int nextImageIndex(HoldingImport imp) throws IOException {
        int max = 0;
        for (Path p : listImages(imp)) {
            String fn = p.getFileName().toString();
            int dot = fn.lastIndexOf('.');
            String stem = dot > 0 ? fn.substring(0, dot) : fn;   // {importId}-{n}
            int dash = stem.lastIndexOf('-');
            if (dash >= 0) {
                try { max = Math.max(max, Integer.parseInt(stem.substring(dash + 1))); }
                catch (NumberFormatException ignored) {}
            }
        }
        return max + 1;
    }

    /** v1.4.2 · 列出某导入已存截图的相对路径(上传态/失败态查看+删除用)。 */
    public List<String> imageRels(long familyId, long importId) {
        HoldingImport imp = importMapper.findById(familyId, importId).orElse(null);
        if (imp == null) return List.of();
        try {
            return listImages(imp).stream().map(p -> relOf(imp, p)).collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("import {} 列图失败: {}", importId, e.toString());
            return List.of();
        }
    }

    /** v1.4.2 · 删一张已上传截图(校验属于本导入)· 递减 imgCount 为实际剩余数。 */
    @Transactional
    public void deleteImage(long familyId, long importId, String rel) {
        HoldingImport imp = importMapper.findById(familyId, importId).orElseThrow(() -> new IllegalArgumentException("导入不存在"));
        if (imp.getFamilyId() != familyId) throw new IllegalArgumentException("无权访问");
        if (rel == null || rel.isBlank()) throw new IllegalArgumentException("缺少图片");
        Path root = Paths.get(props.uploadRoot()).toAbsolutePath().normalize();
        Path target = root.resolve(rel).normalize();
        String expectedPrefix = "family-" + imp.getFamilyId() + "/holdingshots/" + imp.getId() + "-";
        if (!target.startsWith(root) || !rel.startsWith(expectedPrefix)) throw new IllegalStateException("非法路径");
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new IllegalStateException("删除失败: " + e.getMessage());
        }
        importMapper.updateImgCount(familyId, importId, imageRels(familyId, importId).size());
    }

    // ---------- 异步识别 + 三态匹配 ----------

    /** 后台识别(controller 跨 bean 调 = 代理生效 = 真异步)。失败 markScanError,不抛给前端。 */
    @Async
    public void scanAsync(long familyId, long importId) {
        HoldingImport imp = importMapper.findById(familyId, importId).orElse(null);
        if (imp == null) return;
        importMapper.markScanning(familyId, importId);
        try {
            List<Path> images = listImages(imp);
            // 1. 逐图视觉转写 + 记来源图
            record Parsed(VisionLlmClient.ParsedRow row, String shot) {}
            List<Parsed> parsedAll = new ArrayList<>();
            // v1.19.4 · 失败的图必须计数。原来这里只 log.warn 就继续,于是「全都失败」和
            // 「全都成功但确实没持仓」在后面的代码里长得一模一样 —— 都是空的 parsedAll。
            int failedImages = 0;
            Exception lastError = null;
            for (Path p : images) {
                try {
                    byte[] b = Files.readAllBytes(p);
                    String mime = mimeOf(p.getFileName().toString());
                    String rel = relOf(imp, p);
                    for (VisionLlmClient.ParsedRow r : vision.extract(b, mime)) parsedAll.add(new Parsed(r, rel));
                } catch (Exception e) {
                    failedImages++;
                    lastError = e;
                    log.warn("import {} 图 {} 识别失败: {}", importId, p.getFileName(), e.toString());
                }
            }

            // 1b. 一张都没成 → 到此为止,不生成任何比对项。
            //     继续往下走的话,库里每条持仓都会因为「本次没截到」被判成 SOLD,
            //     用户面对的是一张「全部卖出」的表 —— 而真实原因(额度用完 / key 失效)
            //     只躺在服务器日志里。线上真实发生过,详见 SCAN_ERROR 的注释。
            if (!images.isEmpty() && failedImages == images.size()) {
                itemMapper.deleteByImport(familyId, importId);   // 清掉上一轮的残留,别让旧结果冒充本次
                importMapper.markScanError(familyId, importId, friendly(lastError));
                log.error("import {} 全部 {} 张图识别失败,置 SCAN_ERROR", importId, images.size());
                return;
            }
            // 2. 合并去重(按 code 优先,否则归一化名称;保留第一条有市值的)
            Map<String, Parsed> merged = new LinkedHashMap<>();
            for (Parsed p : parsedAll) {
                String key = dedupKey(p.row());
                Parsed exist = merged.get(key);
                if (exist == null || (exist.row().marketValue() == null && p.row().marketValue() != null)) {
                    merged.put(key, p);
                }
            }
            List<Parsed> rows = new ArrayList<>(merged.values());
            // 3. 白名单打标(复用 LensAiTag · 文本)
            List<String> names = rows.stream().map(x -> x.row().name()).collect(Collectors.toList());
            Map<String, LensAiTagService.Tags> tags;
            try { tags = tagService.available(imp.getFamilyId()) ? tagService.suggest(imp.getFamilyId(), names) : Map.of(); }
            catch (Exception e) { tags = Map.of(); }
            // 4. 三态匹配(只比对本账户 SCREENSHOT 活持仓)
            List<StockHolding> existing = holdingMapper.findActiveByAccount(imp.getFamilyId(), imp.getAccountId()).stream()
                    .filter(h -> SYNC_SOURCE.equals(h.getSyncSource()))
                    .collect(Collectors.toList());
            Map<String, StockHolding> existingByKey = new LinkedHashMap<>();
            for (StockHolding h : existing) existingByKey.put(normalize(h.getDisplayName()), h);
            java.util.Set<Long> matchedIds = new java.util.HashSet<>();

            itemMapper.deleteByImport(familyId, importId);  // 重扫覆盖旧结果
            int sort = 0;
            for (Parsed p : rows) {
                VisionLlmClient.ParsedRow r = p.row();
                LensAiTagService.Tags t = tags.get(r.name());
                StockHolding hit = existingByKey.get(normalize(r.name()));
                String state; Long hid = null; BigDecimal oldVal = null;
                if (hit != null) {
                    state = HoldingImportItem.UPDATE; hid = hit.getId();
                    oldVal = marketValueOf(hit);
                    matchedIds.add(hit.getId());
                } else {
                    state = HoldingImportItem.NEW;
                }
                itemMapper.insertOwned(familyId, HoldingImportItem.builder()
                        .importId(importId).parsedName(r.name()).parsedCode(emptyToNull(r.code()))
                        .marketValue(r.marketValue()).confidence(r.confidence())
                        .matchState(state).matchedHid(hid).oldValue(oldVal)
                        .assetClassTag(t == null ? null : t.assetClass())
                        .industryTag(t == null ? null : t.industry())
                        .platformTag(t == null ? null : t.platform())
                        .shotPath(p.shot())
                        .selected(true).sortNo(sort++).build());
            }
            // 5. 消失(库有本次没截到)→ SOLD · 默认 KEEP,用户定夺
            //
            //    v1.19.4 · 但**有图没识别成功时,整体不判卖出**。
            //    「没识别出来」和「卖掉了」是两回事,而这一步分不出来:它只知道某条持仓
            //    没出现在本次结果里。3 张图挂了 1 张,那张里的持仓就会被扣上「卖出?」——
            //    表格其余部分看着完全正常,这种混着假条目的表比全军覆没更容易骗到人。
            //    宁可这一轮不提示卖出(用户下次识别成功时自然会看到),也不给假的卖出建议。
            if (failedImages == 0) {
                for (StockHolding h : existing) {
                    if (matchedIds.contains(h.getId())) continue;
                    itemMapper.insertOwned(familyId, HoldingImportItem.builder()
                            .importId(importId).parsedName(h.getDisplayName())
                            .marketValue(marketValueOf(h)).confidence("high")
                            .matchState(HoldingImportItem.SOLD).matchedHid(h.getId())
                            .oldValue(marketValueOf(h)).userDecision(HoldingImportItem.KEEP)
                            .shotPath(null).selected(true).sortNo(sort++).build());
                }
                importMapper.markReview(familyId, importId);
            } else {
                int total = images.size();
                importMapper.markReviewWithWarning(familyId, importId,
                        total + " 张图里有 " + failedImages + " 张没识别成功(" + friendly(lastError) + ")· "
                        + "这一轮不提示「卖出」—— 没识别出来不等于卖掉了。补传或重新识别后再看。");
            }
        } catch (Exception e) {
            log.error("import {} 扫描失败", importId, e);
            importMapper.markScanError(familyId, importId, friendly(e));
        }
    }

    // ---------- 确认落库 ----------

    /** 用户确认:按 item 增/改/归档 → 估值交接(IMPORT 事件挂 refImportId)→ CONFIRMED。 */
    @Transactional
    public void confirm(long familyId, long importId, Long memberId) {
        HoldingImport imp = importMapper.findById(familyId, importId).orElseThrow(() -> new IllegalArgumentException("导入不存在"));
        if (!HoldingImport.REVIEW.equals(imp.getStatus())) throw new IllegalStateException("当前状态不可确认");
        for (HoldingImportItem it : itemMapper.findByImport(familyId, importId)) {
            if (it.getSelected() != null && !it.getSelected()) continue;
            switch (it.getMatchState()) {
                case HoldingImportItem.NEW -> {
                    if (it.getMarketValue() == null) break;
                    holdingMapper.insertOwned(imp.getFamilyId(), StockHolding.builder()
                            .accountId(imp.getAccountId()).displayName(it.getParsedName())
                            .valuationMode(ValuationMode.MANUAL).shares(BigDecimal.ONE)
                            .manualValue(it.getMarketValue()).manualValueAt(LocalDateTime.now())
                            .syncSource(SYNC_SOURCE).cashLinked(false)
                            .industryTag(it.getIndustryTag()).assetClassTag(it.getAssetClassTag())
                            .build());
                }
                case HoldingImportItem.UPDATE -> {
                    if (it.getMatchedHid() == null || it.getMarketValue() == null) break;
                    holdingMapper.findById(imp.getFamilyId(), it.getMatchedHid()).ifPresent(h -> {
                        // v1.30 · 净值行(场外基金 / 货币基金)的单价是系统按净值写的:老逻辑「份额 = 1、单价 = 市值」
                        //   会在下一次刷新时把单价改回净值 → 市值塌成一个净值数(5,000 元变 4.78 元)。
                        //   改成按截图市值反推份额,单价不动(护栏 v130-IMPORT-NAV-SAFE)。
                        if (h.isNavRow()) {
                            fundHoldingService.applyImportedValue(imp.getFamilyId(), memberId, h, it.getMarketValue());
                            return;
                        }
                        h.setManualValue(it.getMarketValue());
                        h.setShares(BigDecimal.ONE);
                        h.setManualValueAt(LocalDateTime.now());
                        if (it.getIndustryTag() != null) h.setIndustryTag(it.getIndustryTag());
                        if (it.getAssetClassTag() != null) h.setAssetClassTag(it.getAssetClassTag());
                        holdingMapper.update(imp.getFamilyId(), h);
                    });
                }
                case HoldingImportItem.SOLD -> {
                    if (HoldingImportItem.ARCHIVE.equals(it.getUserDecision()) && it.getMatchedHid() != null) {
                        holdingMapper.archive(imp.getFamilyId(), it.getMatchedHid());
                    }
                }
                default -> { }
            }
        }
        // 估值交接:写回当期快照 + 记 IMPORT 估值事件(挂 refImportId,ledger 可展开明细)
        valuationService.refreshOneAccount(imp.getFamilyId(), imp.getAccountId(),
                AccountValuationService.TriggerKind.IMPORT, memberId, importId);
        importMapper.markConfirmed(familyId, importId);
        // v1.5 · 导入的基金后台自动穿透(真实行业/资产分布)· 首次略慢,结果全体共享缓存
        try { penetrationService.penetrateFamilyAsync(imp.getFamilyId()); } catch (Exception ignored) {}
    }

    @Transactional
    public void abandon(long familyId, long importId) { importMapper.updateStatus(familyId, importId, HoldingImport.ABANDONED); }

    /** 用户在比对表编辑一项 · 只覆盖传入的非空字段(selected 显式传即覆盖) */
    @Transactional
    public void editItem(long familyId, long itemId, HoldingImportItem edit) {
        HoldingImportItem it = itemMapper.findById(familyId, itemId).orElseThrow(() -> new IllegalArgumentException("项不存在"));
        HoldingImport imp = importMapper.findById(familyId, it.getImportId()).orElseThrow(() -> new IllegalArgumentException("导入不存在"));
        if (imp.getFamilyId() != familyId) throw new IllegalArgumentException("无权访问");
        if (edit.getParsedName() != null) it.setParsedName(edit.getParsedName());
        if (edit.getMarketValue() != null) it.setMarketValue(edit.getMarketValue());
        if (edit.getMatchState() != null) it.setMatchState(edit.getMatchState());
        if (edit.getMatchedHid() != null) it.setMatchedHid(edit.getMatchedHid());
        if (edit.getIndustryTag() != null) it.setIndustryTag(edit.getIndustryTag());
        if (edit.getAssetClassTag() != null) it.setAssetClassTag(edit.getAssetClassTag());
        if (edit.getPlatformTag() != null) it.setPlatformTag(edit.getPlatformTag());
        if (edit.getUserDecision() != null) it.setUserDecision(edit.getUserDecision());
        if (edit.getSelected() != null) it.setSelected(edit.getSelected());
        itemMapper.update(familyId, it);
    }

    // ---------- 内部 ----------

    private List<Path> listImages(HoldingImport imp) throws IOException {
        Path dir = Paths.get(props.uploadRoot()).toAbsolutePath()
                .resolve("family-" + imp.getFamilyId()).resolve("holdingshots");
        if (!Files.isDirectory(dir)) return List.of();
        String prefix = imp.getId() + "-";
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .sorted().collect(Collectors.toList());
        }
    }

    private String relOf(HoldingImport imp, Path p) {
        return "family-" + imp.getFamilyId() + "/holdingshots/" + p.getFileName();
    }

    private static String mimeOf(String fn) {
        String f = fn.toLowerCase();
        return f.endsWith(".png") ? "image/png" : f.endsWith(".webp") ? "image/webp" : "image/jpeg";
    }

    /** 归一化匹配键:去空格/全角、小写 · 仅用于匹配,展示名保留原文 */
    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '　' || Character.isWhitespace(c)) continue;
            if (c >= '！' && c <= '～') c -= 0xFEE0;   // 全角→半角
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String dedupKey(VisionLlmClient.ParsedRow r) {
        if (r.code() != null && !r.code().isBlank() && !"null".equalsIgnoreCase(r.code().trim())) {
            return "code:" + r.code().trim();
        }
        return "name:" + normalize(r.name());
    }

    private static BigDecimal marketValueOf(StockHolding h) {
        if (h.getManualValue() == null) return null;
        BigDecimal sh = h.getShares() == null ? BigDecimal.ONE : h.getShares();
        return h.getManualValue().multiply(sh);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s.trim())) ? null : s.trim();
    }

    /**
     * 把上游异常翻成用户能<b>照着做</b>的一句话。
     *
     * <p>v1.19.4 之前这里只有两条分支,兜底是「识别失败,请重试」——
     * 而线上真实撞到的是<b>免费额度耗尽</b>(403 {@code AllocationQuota.FreeTierOnly}),
     * 那种情况下「请重试」是<b>错的建议</b>:重试一万次也不会好,必须去平台控制台。
     * 一句让人做无用功的提示,比没有提示更浪费时间。</p>
     *
     * <p>判据按 message 里的特征串走,顺序有讲究:配额那条必须排在 403 前面 ——
     * 阿里云的配额错误本身就是 403,先匹配 403 的话会退化成一句笼统的「被拒绝」。</p>
     */
    static String friendly(Exception e) {
        String m = e == null ? null : e.getMessage();
        if (m == null) return "识别失败,请重试";
        // 配额/欠费:重试无用,只能去控制台。必须在 403/401 之前判(它自己就是 403)
        if (m.contains("AllocationQuota") || m.contains("Free quota exhausted")
                || m.contains("insufficient_quota") || m.contains("Arrearage")
                || m.contains("QuotaExhausted") || m.contains("billing")) {
            return "视觉模型的额度用完了 —— 重试没用,要去该平台控制台充值,"
                 + "或关掉「仅使用免费额度」那个开关;也可以在「AI 接入」页换一个平台";
        }
        if (m.contains("429") || m.contains("Throttling") || m.contains("RateLimit")
                || m.contains("rate_limit") || m.contains("TooManyRequests")) {
            return "调用太频繁被限流了,等一两分钟再试";
        }
        if (m.contains("401") || m.contains("Unauthorized") || m.contains("InvalidApiKey")
                || m.contains("invalid_api_key") || m.contains("AuthenticationError")) {
            return "API key 无效或已过期 —— 去「AI 接入」页重新填一把";
        }
        if (m.contains("404") || m.contains("model_not_found") || m.contains("InvalidParameter")
                || m.contains("ModelNotOpen")) {
            return "这个视觉型号不可用(可能已下线或未开通)—— 去「AI 接入」页换一个型号";
        }
        if (m.contains("403") || m.contains("Forbidden") || m.contains("NoPermission")) {
            return "平台拒绝了这次调用(权限或型号未开通)—— 去该平台控制台确认后再试";
        }
        if (m.contains("Timeout") || m.contains("timeout") || m.contains("timed out")) {
            return "视觉服务超时了 —— 图片太大或网络慢,可以裁掉截图里无关的部分再传";
        }
        if (m.contains("ConnectException") || m.contains("UnknownHost") || m.contains("Connection refused")) {
            return "连不上视觉服务 —— 检查这台机器的外网出口,或稍后再试";
        }
        if (m.contains("key")) return "视觉模型未配置或不可用";
        return "识别失败,请重试";
    }
}
