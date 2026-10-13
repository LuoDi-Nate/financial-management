package com.family.finance.domain.trash;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v1.33 · 回收站里的一个账户(PRD FR-1221 / 1230 ~ 1234)。
 *
 * <p>{@code payload} = 「表名 → 该账户在这张表里的全部原行」的 JSON;{@code rewrites} = 删除时为了
 * 「对方收益不变」写的补录本金 id、置空的引用 —— 恢复时按它还原。彻底清除后两者都为 null,
 * 元数据留着(报表留痕要用)。不进任何计算。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountTrash {
    private Long id;
    private Long familyId;
    private Long accountId;
    private String accountName;
    private String accountType;
    private String periodIds;
    private String rangeLabel;
    private Integer closedCount;
    private Integer transferCount;
    private Integer counterpartCount;
    private String summary;
    private String payload;
    private String rewrites;
    private String backupFile;
    private Long deletedBy;
    private LocalDateTime deletedAt;
    private Long restoredBy;
    private LocalDateTime restoredAt;
    private String restoreNotes;
    private Long purgedBy;
    private LocalDateTime purgedAt;

    public boolean isRestored() { return restoredAt != null; }
    public boolean isPurged() { return purgedAt != null; }
    public boolean isActive() { return restoredAt == null && purgedAt == null; }
}
