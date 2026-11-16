package com.family.finance.domain.holdingimport;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1.4 · 持仓截图导入任务(状态机)。
 *
 * <p>一次导入 = 用户在某账户某开账期上传若干截图 → 视觉识别 → 三态比对 → 确认落库。
 * 持久化以支持:断点续看(退出再进回到 REVIEW)、原图回看、流水明细。</p>
 *
 * <p>status 流转:{@code UPLOADING → SCANNING → REVIEW / SCAN_ERROR → CONFIRMED / ABANDONED}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldingImport {
    private String sourceJson;
    public static final String UPLOADING = "UPLOADING";
    public static final String SCANNING  = "SCANNING";
    public static final String REVIEW    = "REVIEW";
    /**
     * v1.19.4 · 一张图都没识别出来。
     *
     * <p>此前**没有这个状态** —— {@code markScanError} 写的也是 {@code REVIEW},只多一条红字。
     * 于是识别全失败时用户看到的是一张<b>正常的比对表</b>,而那张表里库里每一条持仓都被判成
     * 「卖出?」(因为「本次没截到」)。线上真实发生过:通义千问免费额度用完 → 403 →
     * 两次导入各自把 9 条 / 4 条持仓列成卖出,而用户点了确认。没出事只因为卖出项的默认决定是
     * 「保留」;<b>只要当时勾了归档,这些持仓会被一次清空</b>,页面上却没有任何地方说过
     * 「其实一张图都没识别出来」。</p>
     *
     * <p>所以这一版让它成为独立状态:不生成任何比对项、页面上没有确认按钮,
     * <b>物理上不可能误确认</b>。</p>
     */
    public static final String SCAN_ERROR = "SCAN_ERROR";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String ABANDONED = "ABANDONED";

    private Long id;
    private Long familyId;
    private Long accountId;
    private Long periodId;
    private String status;
    private String visionModel;
    private BigDecimal costEst;
    private Integer imgCount;
    private String scanError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime confirmedAt;
}
