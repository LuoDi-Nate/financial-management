package com.family.finance.repository;

import com.family.finance.domain.backup.BackupLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface BackupLogMapper {

    @Select("""
            SELECT id, family_id, kind, status, size_bytes,
                   location_local, location_remote, error_message,
                   started_at, completed_at
              FROM backup_log
             WHERE family_id = #{familyId}
             ORDER BY started_at DESC
             LIMIT #{limit}
            """)
    List<BackupLog> recent(@Param("familyId") long familyId, @Param("limit") int limit);

    @Select("""
            SELECT id, family_id, kind, status, size_bytes,
                   location_local, location_remote, error_message,
                   started_at, completed_at
              FROM backup_log
             WHERE family_id = #{familyId}
             ORDER BY started_at DESC
             LIMIT 1
            """)
    Optional<BackupLog> latest(@Param("familyId") long familyId);

    /**
     * v1.32 · 立即备份开始时先写一行 RUNNING(页面上别人打开也看得见「正在备份」),跑完再 {@link #finish}。
     * 只给这个家写 —— 手动备份是这个家的人点的。
     */
    @Insert("""
            INSERT INTO backup_log (family_id, kind, status, location_local, started_at)
            SELECT f.id, 'MANUAL', 'RUNNING', #{e.locationLocal}, NOW(3) FROM family f WHERE f.id = #{familyId}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "e.id")
    int insertRunning(@Param("familyId") long familyId, @Param("e") BackupLog e);

    default void insertRunningOwned(long familyId, BackupLog e) {
        if (insertRunning(familyId, e) != 1) throw new IllegalStateException("家庭 " + familyId + " 不存在");
    }

    @Update("""
            UPDATE backup_log
               SET status = #{e.status}, size_bytes = #{e.sizeBytes}, location_local = #{e.locationLocal},
                   error_message = #{e.errorMessage}, completed_at = NOW(3)
             WHERE id = #{e.id} AND family_id = #{familyId}
            """)
    int finish(@Param("familyId") long familyId, @Param("e") BackupLog e);

    /** 最近一次手动备份(两次至少隔 1 分钟,FR-1173) */
    @Select("""
            SELECT id, family_id, kind, status, size_bytes,
                   location_local, location_remote, error_message,
                   started_at, completed_at
              FROM backup_log
             WHERE family_id = #{familyId} AND kind = 'MANUAL'
             ORDER BY started_at DESC
             LIMIT 1
            """)
    Optional<BackupLog> latestManual(@Param("familyId") long familyId);
}
