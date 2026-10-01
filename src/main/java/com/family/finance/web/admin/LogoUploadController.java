package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.config.AppProperties;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.FamilyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Logo 上传 · v0.1 简化版 · v1.28.3 修两处(issue #25)。
 *
 * 前端 Canvas 先把图缩到 256px 再编码,后端只做 4 件事:
 *  1. 按<b>文件头魔数</b>认格式:WebP(RIFF…WEBP)/ PNG / JPEG —— 不信浏览器报的 Content-Type
 *  2. 大小 1..200KB
 *  3. 写到 ${app.upload-root}/family-{id}/logo-{内容指纹}.{webp|png|jpg},落盘前 normalize 校验路径不逃出根目录
 *  4. 删掉这个家庭目录下旧的 logo 文件
 *
 * <p><b>v1.28.3 · 为什么不再只收 WebP</b>:Safari 以及 iPhone / iPad 上的所有浏览器(都是 WebKit)
 * 不会把 Canvas 编码成 WebP —— {@code toBlob(…, 'image/webp')} 不报错,悄悄给一张 PNG。
 * 原来这里只认 image/webp,这些浏览器上传永远是「400 must be image/webp」,换哪个 iOS 浏览器都一样。</p>
 *
 * <p><b>v1.28.3 · 为什么文件名带内容指纹</b>:原来永远写同一个 {@code logo.webp},而 /uploads 有 7 天公共缓存 ——
 * 换一张图之后,浏览器照样显示缓存里的旧图(「上传成功但变成之前设置过的那张」)。
 * 名字随内容变,地址就变,缓存自然失效。老家庭的 {@code logo.webp} 路径照常能读,下次上传时换成新名字。</p>
 *
 * 详见 PRD § FR-1 Logo 上传规则;TDD § 7;prd/v1.28.md §16。
 */
@Controller
@RequestMapping("/admin/family/logo")
@RequiredArgsConstructor
@Slf4j
public class LogoUploadController {

    private static final long MIN_BYTES = 1;
    private static final long MAX_BYTES = 200L * 1024;     // 200KB
    private static final java.util.regex.Pattern OLD_LOGO = java.util.regex.Pattern.compile("logo(-[0-9a-f]{10})?\\.(webp|png|jpg)");

    private final AppProperties props;
    private final FamilyService familyService;
    private final AuditLogService auditLogService;

    /** 按文件头认格式,返回扩展名;认不出返回 null。 */
    static String sniffExtension(byte[] head) {
        if (head == null) return null;
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return "webp";
        if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) return "png";
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return "jpg";
        return null;
    }

    @PostMapping
    public ResponseEntity<String> upload(@RequestParam("logo") MultipartFile file,
                                         @AuthenticationPrincipal MemberPrincipal me) throws IOException {
        if (file == null || file.isEmpty())
            return ResponseEntity.badRequest().body("没有收到图片");

        long size = file.getSize();
        if (size < MIN_BYTES || size > MAX_BYTES)
            return ResponseEntity.badRequest().body("压缩后仍超过 200KB,换一张简单些的图试试");

        byte[] bytes = file.getBytes();
        String ext = sniffExtension(java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 12)));
        if (ext == null)
            return ResponseEntity.badRequest().body("只支持 WebP / PNG / JPEG 图片");

        Path uploadRoot = Paths.get(props.uploadRoot()).toAbsolutePath().normalize();
        Files.createDirectories(uploadRoot);
        Path familyDir = uploadRoot.resolve("family-" + me.getFamilyId());
        Files.createDirectories(familyDir);
        String name = "logo-" + fingerprint(bytes) + "." + ext;
        Path target = familyDir.resolve(name).normalize();

        // path traversal 防御:目标路径必须严格在 uploadRoot 之下
        if (!target.startsWith(uploadRoot)) {
            log.warn("[LogoUpload] refused path traversal: {}", target);
            return ResponseEntity.badRequest().body("path escape");
        }

        Files.write(target, bytes);
        removeOldLogos(familyDir, name);

        String relativePath = "family-" + me.getFamilyId() + "/" + name;
        familyService.updateBrandLogo(me.getFamilyId(), relativePath);
        auditLogService.write(me.getFamilyId(), me.getMemberId(), AuditLogType.LOGO_UPLOAD,
                "family", me.getFamilyId(),
                "Logo 上传 · %s · %d bytes".formatted(ext, size),
                java.util.Map.of("path", relativePath, "size", size));

        return ResponseEntity.ok("ok");
    }

    private static String fingerprint(byte[] bytes) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(d).substring(0, 10);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 删掉这个家庭目录下除 keep 以外的 logo 文件(只认 logo.* / logo-指纹.* 这两种名字,别的不碰)。 */
    private static void removeOldLogos(Path familyDir, String keep) throws IOException {
        try (var files = Files.list(familyDir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                if (!n.equals(keep) && OLD_LOGO.matcher(n).matches()) Files.deleteIfExists(f);
            }
        }
    }

    /**
     * v0.2 FR-1/FR-34:切换品牌预设图标 — 一并清空自定义 logo_path,
     * 让 web favicon / iOS apple-touch / PWA manifest 三处全部跟随同一张预设。
     */
    @PostMapping("/preset")
    public String selectPreset(@RequestParam("preset") String preset,
                               @AuthenticationPrincipal MemberPrincipal me,
                               RedirectAttributes ra) {
        try {
            familyService.updateLogoPreset(me.getFamilyId(), preset);
            auditLogService.write(me.getFamilyId(), me.getMemberId(), AuditLogType.LOGO_UPLOAD,
                    "family", me.getFamilyId(),
                    "切换品牌预设图标 → " + preset,
                    java.util.Map.of("preset", preset));
            ra.addFlashAttribute("flash", "已切换为预设 " + preset);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flash", "✗ " + e.getMessage());
        }
        return "redirect:/admin/family";
    }

    @PostMapping("/remove")
    public String remove(@AuthenticationPrincipal MemberPrincipal me) throws IOException {
        Path uploadRoot = Paths.get(props.uploadRoot()).toAbsolutePath().normalize();
        Path familyDir = uploadRoot.resolve("family-" + me.getFamilyId()).normalize();
        // v1.28.3 · 文件名带指纹了,不再只删 logo.webp
        if (familyDir.startsWith(uploadRoot) && Files.isDirectory(familyDir)) {
            removeOldLogos(familyDir, "");
        }
        familyService.updateBrandLogo(me.getFamilyId(), null);
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.LOGO_UPLOAD,
                "family", me.getFamilyId(), "Logo 已移除");
        return "redirect:/admin/family";
    }
}
