package com.family.finance.domain.backup;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackupLog {
    private Long id;
    private Long familyId;
    private String kind;            // WEEKLY(systemd 周备)/ MANUAL / DAILY(Docker 备份容器 · V70)
    private String status;          // SUCCESS / FAILED / RUNNING
    private Long sizeBytes;
    private String locationLocal;
    private String locationRemote;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
