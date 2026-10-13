package com.family.finance.service.backup;

import com.family.finance.domain.backup.BackupLog;
import com.family.finance.repository.BackupLogMapper;
import com.family.finance.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.32 · 立即备份(PRD FR-1170 ~ 1173 · tech-design 选型七)。
 *
 * <p>真正跑 mysqldump 的那条路由 e2e 在 beta 上验(页面 + backup_log 行);这里钉住不依赖外部命令的规矩:
 * 校验(能解压 + 有建表语句)、1 分钟间隔、失败也写一行且带原因、文件名落进自动备份的滚动删除前缀。</p>
 */
class BackupNowServiceTest {

    BackupLogMapper mapper = mock(BackupLogMapper.class);

    private BackupNowService svc(Path dir) {
        return new BackupNowService(mapper, mock(AuditLogService.class), "127.0.0.1", "3306", "finance", "finance", "x",
                dir == null ? "" : dir.toString());
    }

    private static void gz(Path f, String content) throws IOException {
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(f))) {
            o.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void verify_countsCreateTable_andRejectsCorruptFile(@TempDir Path tmp) throws IOException {
        Path ok = tmp.resolve("ok.sql.gz");
        gz(ok, "-- dump\nCREATE TABLE `a` (\n  id int\n);\nCREATE TABLE `b` (x int);\nINSERT INTO a VALUES (1);\n");
        assertThat(BackupNowService.verify(ok)).isEqualTo(2);
        Path empty = tmp.resolve("empty.sql.gz");
        gz(empty, "-- mysqldump: Got error\n");
        assertThat(BackupNowService.verify(empty)).as("没有建表语句 → 0,调用方记失败").isZero();
        Path bad = tmp.resolve("bad.sql.gz");
        byte[] bytes = Files.readAllBytes(ok);
        Files.write(bad, java.util.Arrays.copyOf(bytes, bytes.length - 6));   // 截断 → 校验和对不上
        assertThatThrownBy(() -> BackupNowService.verify(bad)).isInstanceOf(IOException.class);
    }

    @Test
    void secondManualBackupWithinAMinute_isRefused_withoutWritingARow(@TempDir Path tmp) {
        when(mapper.latestManual(1L)).thenReturn(Optional.of(BackupLog.builder().kind("MANUAL")
                .startedAt(LocalDateTime.now().minusSeconds(20)).build()));
        assertThatThrownBy(() -> svc(tmp).runNow(1L, 7L)).hasMessageContaining("1 分钟");
        verify(mapper, never()).insertRunningOwned(anyLong(), any());
    }

    @Test
    void unwritableDir_failsWithTheRealReason_andStillLogsARow(@TempDir Path tmp) {
        when(mapper.latestManual(1L)).thenReturn(Optional.empty());
        doAnswer(inv -> { ((BackupLog) inv.getArgument(1)).setId(77L); return null; })
                .when(mapper).insertRunningOwned(eq(1L), any());
        Path missing = tmp.resolve("no-such-dir");
        var r = svc(missing).runNow(1L, 7L);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("不存在").contains(missing.toString());
        ArgumentCaptor<BackupLog> row = ArgumentCaptor.forClass(BackupLog.class);
        verify(mapper).finish(eq(1L), row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(row.getValue().getErrorMessage()).contains("不存在");
    }

    @Test
    void fileNameFallsUnderAutoBackupRetentionPrefix(@TempDir Path tmp) {
        // systemd 的自动备份按 dump-*.sql.gz 滚动删、Docker 按 finance-*.sql.gz —— 手动的文件名必须落在这两个前缀里
        String p = svc(tmp).filePrefix();
        assertThat(p.equals("dump-manual-") || p.equals("finance-manual-")).isTrue();
        assertThat(svc(tmp).dir()).isEqualTo(tmp);
    }
}
