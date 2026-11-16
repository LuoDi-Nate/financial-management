package com.family.finance.domain.allocation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v0.4 FR-62b · AI 调仓建议缓存实体 · 30 天节流。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RebalanceAdviceCache {
    private String sourceJson;
    private Long id;
    private Long familyId;
    private String anchorCode;
    /** AI 返回的 JSON: {actions:[{from_account, to_account, amount, reason}], narrative} */
    private String contentJson;
    /** sha256(prompt) · 数据未变可命中缓存(可选 · 简单做先不用) */
    private String promptHash;
    private LocalDateTime generatedAt;
    /** v1.28 · 生成这份建议的那次调用(llm_prompt_record.id)· 空 = 本版之前生成的老建议 */
    private Long promptRecordId;
}
