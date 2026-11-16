package com.family.finance.repository;

import com.family.finance.domain.holdingimport.HoldingImport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

/**
 * holding_import 表 Mapper · v1.4。
 */
@Mapper
public interface HoldingImportMapper {

    String COLS = """
            id, family_id AS familyId, account_id AS accountId, period_id AS periodId, status,
            vision_model AS visionModel, cost_est AS costEst, img_count AS imgCount, scan_error AS scanError, source_json AS sourceJson,
            created_at AS createdAt, updated_at AS updatedAt, confirmed_at AS confirmedAt
            """;

    @Select("SELECT " + COLS + " FROM holding_import"
          + " WHERE family_id = #{familyId} AND id = #{id}")
    Optional<HoldingImport> findById(@Param("familyId") long familyId, @Param("id") long id);

    /**
     * 该账户是否有未完成的导入 → 断点续看 · 取最新一条。
     *
     * <p>v1.19.4 把 {@code SCAN_ERROR} 也算未完成:识别失败是<b>要用户处理</b>的状态
     * (看原因、删错图、重新识别),不算进来的话用户退出再进就会新开一个空导入,
     * 上一次的失败原因和已传的图全被甩在后面看不到了。</p>
     */
    @Select("SELECT " + COLS + " FROM holding_import"
            + " WHERE family_id = #{familyId} AND account_id = #{accountId}"
            + " AND status IN ('UPLOADING','SCANNING','REVIEW','SCAN_ERROR')"
            + " ORDER BY id DESC LIMIT 1")
    Optional<HoldingImport> findOpenByAccount(@Param("familyId") long familyId,
                                             @Param("accountId") long accountId);

    @Insert("""
            INSERT INTO holding_import (family_id, account_id, period_id, status, vision_model, cost_est, img_count)
            VALUES (#{familyId}, #{accountId}, #{periodId}, #{status}, #{visionModel}, #{costEst}, #{imgCount})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(HoldingImport imp);

    @Update("UPDATE holding_import SET status = #{status} WHERE family_id = #{familyId} AND id = #{id}")
    int updateStatus(@Param("familyId") long familyId, @Param("id") long id, @Param("status") String status);

    @Update("UPDATE holding_import SET img_count = #{imgCount} WHERE family_id = #{familyId} AND id = #{id}")
    int updateImgCount(@Param("familyId") long familyId, @Param("id") long id, @Param("imgCount") int imgCount);

    @Update("UPDATE holding_import SET status = 'REVIEW' WHERE family_id = #{familyId} AND id = #{id}")
    int markReview(@Param("familyId") long familyId, @Param("id") long id);

    @Update("UPDATE holding_import SET status = 'SCANNING', scan_error = NULL WHERE family_id = #{familyId} AND id = #{id}")
    int markScanning(@Param("familyId") long familyId, @Param("id") long id);

    @Update("UPDATE holding_import SET source_json=#{sourceJson}, vision_model=#{model} WHERE family_id=#{familyId} AND id=#{id}")
    int source(@Param("familyId") long familyId, @Param("id") long id, @Param("sourceJson") String sourceJson, @Param("model") String model);

    /**
     * v1.19.4 · 一张都没识别出来 → 进 {@code SCAN_ERROR},<b>不是 REVIEW</b>。
     *
     * <p>原来这里写的是 {@code status='REVIEW'} —— 识别全军覆没,页面却给出一张
     * 「每条持仓都是卖出?」的比对表和一个确认按钮。差一次勾选就会清空真实持仓。</p>
     */
    @Update("UPDATE holding_import SET status = 'SCAN_ERROR', scan_error = #{err} WHERE family_id = #{familyId} AND id = #{id}")
    int markScanError(@Param("familyId") long familyId, @Param("id") long id, @Param("err") String err);

    /**
     * v1.19.4 · 识别成功了一部分:照常进 REVIEW,但把「有几张没成」记下来给页面提示。
     *
     * <p>这种情况比全失败更容易骗人:表格看着完全正常,只是混着几条**假的**「卖出?」——
     * 没识别出来的那张图里的持仓,在比对时会被当成「本次没截到」。所以除了这条提示,
     * service 那边还会在有图失败时**整体不判卖出**。</p>
     */
    @Update("UPDATE holding_import SET status = 'REVIEW', scan_error = #{warn} WHERE family_id = #{familyId} AND id = #{id}")
    int markReviewWithWarning(@Param("familyId") long familyId, @Param("id") long id, @Param("warn") String warn);

    @Update("UPDATE holding_import SET status = 'CONFIRMED', confirmed_at = NOW(3) WHERE family_id = #{familyId} AND id = #{id}")
    int markConfirmed(@Param("familyId") long familyId, @Param("id") long id);
}
