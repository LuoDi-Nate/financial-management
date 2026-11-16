package com.family.finance.web.holdingimport;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.holdingimport.HoldingImport;
import com.family.finance.domain.holdingimport.HoldingImportItem;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.holdingimport.HoldingImportService;
import com.family.finance.service.holdingimport.VisionLlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1.4 · 持仓截图导入 · 填报页选中账户后进入。
 *
 * <p>页面 GET /entry/import/{accountId}(startOrResume · 按状态渲染 上传/扫描/比对);
 * AJAX 上传/扫描/轮询/编辑;确认落库。</p>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class HoldingImportController {

    private final HoldingImportService importService;
    private final AccountMapper accountMapper;
    private final VisionLlmClient vision;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.service.llmtrace.PromptPeekLinks promptPeek;

    private Account requireAccount(long familyId, long accountId) {
        Account a = accountMapper.findById(familyId, accountId).orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        if (a.getFamilyId() != familyId) throw new IllegalArgumentException("无权访问");
        return a;
    }

    private HoldingImport requireImport(long familyId, long importId) {
        HoldingImport imp = importService.get(familyId, importId).orElseThrow(() -> new IllegalArgumentException("导入不存在"));
        if (imp.getFamilyId() != familyId) throw new IllegalArgumentException("无权访问");
        return imp;
    }

    // ---------- 页面 ----------

    /**
     * v1.4.2 · 消毒来源地址:只允许站内相对路径(防开放重定向)。用于导入完成后「回到进入的页面」。
     */
    private static String safeLocalPath(String from, String fallback) {
        if (from == null || from.isBlank()) return fallback;
        if (from.startsWith("/") && !from.startsWith("//") && from.matches("/[A-Za-z0-9/_?=&.\\-]*")) return from;
        return fallback;
    }

    @GetMapping("/entry/import/{accountId}")
    public String page(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId,
                       @RequestParam(required = false) String from, Model model) {
        Account account = requireAccount(me.getFamilyId(), accountId);
        model.addAttribute("from", safeLocalPath(from, "/entry"));   // v1.4.2 · 回来源页
        boolean available=vision.available();
        var existing=importService.open(me.getFamilyId(),accountId);
        if (!available && existing.isEmpty()) {
            model.addAttribute("account", account);
            model.addAttribute("visionUnavailable", true);
            return "holdingimport/import";
        }
        HoldingImport imp;
        try {
            imp = existing.orElseGet(() -> importService.startOrResume(me.getFamilyId(), accountId));
        } catch (IllegalStateException e) {
            // 没有开账期 → 友好提示,不 500
            model.addAttribute("account", account);
            model.addAttribute("noPeriod", true);
            return "holdingimport/import";
        }
        model.addAttribute("account", account);
        model.addAttribute("imp", imp);
        model.addAttribute("imageRels", importService.imageRels(me.getFamilyId(), imp.getId()));   // v1.4.2 · 查看/删除已上传图
        model.addAttribute("visionModel", vision.model());
        model.addAttribute("visionPlatform", vision.platformLabel());   // v1.13 · 让用户知道截图发去了哪家平台
        model.addAttribute("visionPlatformCode",vision.platformCode());
        source(me.getFamilyId(),imp,model);
        if (HoldingImport.REVIEW.equals(imp.getStatus())) {
            model.addAttribute("items", importService.items(me.getFamilyId(), imp.getId()));
            model.addAttribute("industryTags", com.family.finance.domain.lens.IndustryTag.values());
            model.addAttribute("assetClasses", com.family.finance.domain.lens.AssetClass.values());
        }
        return "holdingimport/import";
    }

    // ---------- AJAX ----------

    @PostMapping("/entry/import/{importId}/upload")
    @ResponseBody
    public Map<String, Object> upload(@AuthenticationPrincipal MemberPrincipal me,
                                      @PathVariable long importId,
                                      @RequestParam("files") MultipartFile[] files) {
        HoldingImport imp = requireImport(me.getFamilyId(), importId);
        int saved = 0;
        // v1.19.5 · 把存下来的相对路径**还给前端**。此前这里丢弃了 saveImage 的返回值,
        // 于是刚上传的缩略图拿不到服务器路径 —— 既没法生成删除请求(要 rel),
        // 也没法指向真正的大图。结果是那批图只能干看着:点不开、删不掉,
        // 得刷新一次页面等服务端把画廊渲染出来才有这两个能力。
        java.util.List<String> rels = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            if (f.isEmpty()) continue;
            try {
                rels.add(importService.saveImage(imp, f.getBytes(), f.getContentType()));
                saved++;
            } catch (Exception e) {
                log.warn("import {} 存图失败: {}", importId, e.toString());
            }
        }
        return Map.of("importId", importId, "imgCount", imp.getImgCount() == null ? 0 : imp.getImgCount(),
                "saved", saved, "rels", rels);
    }

    @PostMapping("/entry/import/{importId}/scan")
    @ResponseBody
    public Map<String, Object> scan(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long importId) {
        HoldingImport imp = requireImport(me.getFamilyId(), importId);
        if (imp.getImgCount() == null || imp.getImgCount() == 0) return Map.of("error", "请先上传截图");
        importService.scanAsync(me.getFamilyId(), importId);   // 跨 bean → 真异步
        return Map.of("status", HoldingImport.SCANNING);
    }

    /** v1.4.2 · 删一张已上传截图(上传态/识别失败态可用)· 返回剩余张数 */
    @PostMapping("/entry/import/{importId}/image/delete")
    @ResponseBody
    public Map<String, Object> deleteImage(@AuthenticationPrincipal MemberPrincipal me,
                                           @PathVariable long importId,
                                           @RequestParam String rel) {
        importService.deleteImage(me.getFamilyId(), importId, rel);
        HoldingImport imp = requireImport(me.getFamilyId(), importId);
        return Map.of("ok", true, "imgCount", imp.getImgCount() == null ? 0 : imp.getImgCount());
    }

    @GetMapping("/entry/import/{importId}/status")
    @ResponseBody
    public Map<String, Object> status(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long importId) {
        HoldingImport imp = requireImport(me.getFamilyId(), importId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", imp.getStatus());
        r.put("scanError", imp.getScanError());
        if (HoldingImport.REVIEW.equals(imp.getStatus())) {
            r.put("itemCount", importService.items(me.getFamilyId(), importId).size());
        }
        return r;
    }

    /** 用户在比对表编辑一项(市值/标签/勾选/卖出定夺/改匹配) */
    @PostMapping("/entry/import/item/{itemId}")
    @ResponseBody
    public Map<String, Object> editItem(@AuthenticationPrincipal MemberPrincipal me,
                                        @PathVariable long itemId,
                                        @RequestParam(required = false) BigDecimal marketValue,
                                        @RequestParam(required = false) String parsedName,
                                        @RequestParam(required = false) String matchState,
                                        @RequestParam(required = false) Long matchedHid,
                                        @RequestParam(required = false) String industryTag,
                                        @RequestParam(required = false) String assetClassTag,
                                        @RequestParam(required = false) String platformTag,
                                        @RequestParam(required = false) String userDecision,
                                        @RequestParam(required = false) Boolean selected) {
        importService.editItem(me.getFamilyId(), itemId, HoldingImportItem.builder()
                .id(itemId).marketValue(marketValue).parsedName(parsedName).matchState(matchState)
                .matchedHid(matchedHid).industryTag(industryTag).assetClassTag(assetClassTag)
                .platformTag(platformTag).userDecision(userDecision).selected(selected).build());
        return Map.of("ok", true);
    }

    @PostMapping("/entry/import/{importId}/confirm")
    public String confirm(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long importId,
                          @RequestParam(required = false) String from) {
        requireImport(me.getFamilyId(), importId);
        importService.confirm(me.getFamilyId(), importId, me.getMemberId());
        return "redirect:" + safeLocalPath(from, "/entry");   // v1.4.2 · 回进入页(默认填报页)
    }

    @PostMapping("/entry/import/{importId}/abandon")
    public String abandon(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long importId,
                          @RequestParam(required = false) String from) {
        requireImport(me.getFamilyId(), importId);
        importService.abandon(me.getFamilyId(), importId);
        return "redirect:" + safeLocalPath(from, "/entry");   // v1.4.2 · 回进入页(默认填报页)
    }

    /** v1.4 · 从流水「看明细」进入:逐项变化 + 当时上传的原图(只读) */
    @GetMapping("/entry/import/{importId}/detail")
    public String detail(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long importId, Model model) {
        HoldingImport imp = requireImport(me.getFamilyId(), importId);
        model.addAttribute("imp", imp);
        model.addAttribute("account", requireAccount(me.getFamilyId(), imp.getAccountId()));
        source(me.getFamilyId(),imp,model);
        java.util.List<HoldingImportItem> items = importService.items(me.getFamilyId(), importId);
        model.addAttribute("items", items);
        model.addAttribute("shots", items.stream().map(HoldingImportItem::getShotPath)
                .filter(java.util.Objects::nonNull).distinct().toList());
        return "holdingimport/detail";
    }

    private void source(long fid,HoldingImport imp,Model model) {
        String legacy=HoldingImport.UPLOADING.equals(imp.getStatus()) ? vision.platformLabel()+" · "+vision.model()
            : "旧版型号 · "+(imp.getVisionModel()==null ? "未记录" : imp.getVisionModel());
        model.addAttribute("visionSourceLabel",com.family.finance.service.llmtrace.LlmSource.label(imp.getSourceJson(),legacy));
        if(promptPeek!=null) model.addAttribute("importPeekSrc",promptPeek.of(fid,
            com.family.finance.service.llmtrace.LlmSource.recordId(imp.getSourceJson()),
            com.family.finance.service.llmtrace.PromptSurface.VISION_IMPORT,imp.getUpdatedAt(),null));
    }
}
