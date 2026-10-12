package com.family.finance.service.backup;

import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.backup.BackupLog;
import com.family.finance.repository.BackupLogMapper;
import com.family.finance.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * v1.32 · 立即备份(PRD FR-1170 ~ 1173 · tech-design v1.32 选型七)。
 *
 * <p>应用自己起一个 {@code mysqldump} 子进程,输出经 GZIP 写进<b>和自动备份同一个目录</b>,
 * 写完用与 Docker 备份容器相同的标准校验(能完整解压 + 至少一句 {@code CREATE TABLE}),
 * 结果写进 {@code backup_log}(类型 MANUAL)。同步执行 —— v1.33「删除账户前先备份、成功才删」直接复用。</p>
 *
 * <ul>
 *   <li>同一时间只跑一个(进程内锁);两次手动备份至少隔 1 分钟(防手抖连点写满盘)。</li>
 *   <li>10 分钟没跑完就杀掉子进程,记失败。</li>
 *   <li>密码经子进程环境变量 {@code MYSQL_PWD} 传,不出现在命令行。</li>
 *   <li>失败时 mysqldump 的原话(stderr 前 400 字)原样记进 {@code error_message} —— 上游原话不许被兜底文案盖住。</li>
 *   <li>文件名落在两种部署自动备份的滚动删除前缀里(systemd {@code dump-*},Docker {@code finance-*}),
 *       保留天数到了一起删,不用另写清理(FR-1172)。</li>
 * </ul>
 */
@Slf4j
@Service
public class BackupNowService {

    static final Duration MIN_GAP = Duration.ofSeconds(60);
    static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BackupLogMapper backupLogMapper;
    private final AuditLogService auditLogService;
    private final ReentrantLock lock = new ReentrantLock();

    private final String dbHost;
    private final String dbPort;
    private final String dbName;
    private final String dbUser;
    private final String dbPass;
    private final String backupDirEnv;

    public BackupNowService(BackupLogMapper backupLogMapper, AuditLogService auditLogService,
                            @Value("${DB_HOST:127.0.0.1}") String dbHost,
                            @Value("${DB_PORT:3306}") String dbPort,
                            @Value("${DB_NAME:finance}") String dbName,
                            @Value("${DB_USER:finance}") String dbUser,
                            @Value("${DB_PASS:finance}") String dbPass,
                            @Value("${BACKUP_DIR:}") String backupDirEnv) {
        this.backupLogMapper = backupLogMapper;
        this.auditLogService = auditLogService;
        this.dbHost = dbHost;
        this.dbPort = dbPort;
        this.dbName = dbName;
        this.dbUser = dbUser;
        this.dbPass = dbPass;
        this.backupDirEnv = backupDirEnv;
    }

    /** 部署方式:容器里有 {@code /.dockerenv}(与 FutuOpendManager 同一个判据) */
    public boolean isDocker() {
        return Files.exists(Path.of("/.dockerenv"));
    }

    /** 备份目录:配了 BACKUP_DIR 用它(systemd 的 /etc/finance.env 里就有);没配按部署方式给默认 */
    public Path dir() {
        if (backupDirEnv != null && !backupDirEnv.isBlank()) return Path.of(backupDirEnv.trim());
        return isDocker() ? Path.of("/data/backups") : Path.of("/var/backup/finance");
    }

    /** 应用对备份目录有没有写权限(页面上提前说,别等点了才失败) */
    public boolean dirWritable() {
        Path d = dir();
        return Files.isDirectory(d) && Files.isWritable(d);
    }

    String filePrefix() {
        return isDocker() ? "finance-manual-" : "dump-manual-";
    }

    /**
     * @param ok        成功与否
     * @param file      文件(成功时)
     * @param bytes     大小(成功时)
     * @param error     失败原因(原话)
     * @param logId     backup_log 的 id
     */
    public record Result(boolean ok, String file, long bytes, String error, Long logId) {
    }

    /**
     * 立即备份一次。拒绝(正在备份 / 不到 1 分钟)抛 {@link IllegalStateException},不写记录;
     * 真正跑了的,不管成败都有一行 {@code backup_log}。
     */
    public Result runNow(long familyId, long memberId) {
        if (!lock.tryLock()) throw new IllegalStateException("正在备份,等它做完再点");
        try {
            Optional<BackupLog> last = backupLogMapper.latestManual(familyId);
            if (last.isPresent() && last.get().getStartedAt() != null
                    && last.get().getStartedAt().isAfter(LocalDateTime.now().minus(MIN_GAP))) {
                throw new IllegalStateException("刚备份过,1 分钟后再点(防止连点写出一堆文件)");
            }
            return doBackup(familyId, memberId);
        } finally {
            lock.unlock();
        }
    }

    private Result doBackup(long familyId, long memberId) {
        Path dir = dir();
        Path file = dir.resolve(filePrefix() + LocalDateTime.now().format(TS) + ".sql.gz");
        BackupLog row = BackupLog.builder().locationLocal(file.toString()).build();
        backupLogMapper.insertRunningOwned(familyId, row);
        String error = null;
        long bytes = 0;
        try {
            if (!Files.isDirectory(dir)) {
                throw new IOException("备份目录 " + dir + " 不存在");
            }
            if (!Files.isWritable(dir)) {
                throw new IOException("应用对备份目录 " + dir + " 没有写权限");
            }
            dump(file);
            int tables = verify(file);
            if (tables <= 0) throw new IOException("备份文件里没有建表语句,不算成功");
            bytes = Files.size(file);
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // 删不掉也不影响结论:这次记失败
            }
        }
        row.setStatus(error == null ? "SUCCESS" : "FAILED");
        row.setSizeBytes(error == null ? bytes : null);
        row.setErrorMessage(error == null ? null : clip(error, 500));
        backupLogMapper.finish(familyId, row);
        auditLogService.record(familyId, memberId, AuditLogType.BACKUP, "backup_log", row.getId(),
                error == null ? "立即备份 · 成功 · " + file.getFileName() + " · " + (bytes / 1024) + " KB"
                        : "立即备份 · 失败 · " + clip(error, 160));
        if (error != null) log.warn("立即备份失败 · family={} · {}", familyId, clip(error, 400));
        return new Result(error == null, error == null ? file.toString() : null, bytes, error, row.getId());
    }

    /** mysqldump → GZIP → 文件。退出码非 0 / 超时 → IOException(带 mysqldump 原话) */
    void dump(Path file) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(List.of("mysqldump",
                "--no-tablespaces", "--single-transaction", "--quick", "--default-character-set=utf8mb4",
                "-h", dbHost, "-P", dbPort, "-u", dbUser, dbName));
        pb.environment().put("MYSQL_PWD", dbPass == null ? "" : dbPass);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("起不来 mysqldump(这台机器上没装 MySQL 客户端?):" + e.getMessage(), e);
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread errPump = new Thread(() -> copyQuietly(p.getErrorStream(), err), "backup-stderr");
        errPump.setDaemon(true);
        errPump.start();
        Thread watchdog = new Thread(() -> {
            try {
                if (!p.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) p.destroyForcibly();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "backup-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file), 1 << 16)) {
            p.getInputStream().transferTo(out);
        }
        boolean finished = p.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        errPump.join(2000);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("mysqldump 超过 " + TIMEOUT.toMinutes() + " 分钟没做完,已停止");
        }
        int code = p.exitValue();
        if (code != 0) {
            String msg = err.toString(StandardCharsets.UTF_8).trim();
            throw new IOException("mysqldump 退出码 " + code + (msg.isEmpty() ? "" : ":" + clip(msg, 400)));
        }
    }

    /** 与 Docker 备份容器同一套校验:能完整解压(GZIP 校验和)+ 数一数 CREATE TABLE */
    static int verify(Path file) throws IOException {
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file), 1 << 16), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("CREATE TABLE")) n++;
            }
        }
        return n;
    }

    private static void copyQuietly(InputStream in, ByteArrayOutputStream out) {
        try (in) {
            byte[] buf = new byte[4096];
            int k;
            while ((k = in.read(buf)) >= 0) {
                if (out.size() < 8192) out.write(buf, 0, Math.min(k, 8192 - out.size()));
            }
        } catch (IOException ignored) {
            // 进程被杀时流会断,原因由调用方给
        }
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
