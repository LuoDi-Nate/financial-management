package com.family.finance.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * v1.19 · 接口调用审计。
 *
 * <p><b>不记返回体、不记请求参数里的金额</b> —— 返回体就是家底数据,
 * 记进审计表只是多造一个泄漏面。这里只回答「谁、什么时候、调了哪个接口、结果如何」。</p>
 */
@Mapper
public interface AskAuditMapper {

    @Insert("""
            INSERT INTO ask_access_audit
                (family_id, token_prefix, tool_name, result, src_ip, user_agent, duration_ms)
            VALUES
                (#{familyId}, #{tokenPrefix}, #{toolName}, #{result}, #{srcIp}, #{userAgent}, #{durationMs})
            """)
    int insert(@Param("familyId") long familyId,
               @Param("tokenPrefix") String tokenPrefix,
               @Param("toolName") String toolName,
               @Param("result") String result,
               @Param("srcIp") String srcIp,
               @Param("userAgent") String userAgent,
               @Param("durationMs") Integer durationMs);

    /** 管理页「最近调用」 */
    record Row(String tokenPrefix, String toolName, String result,
               String srcIp, String userAgent, Integer durationMs, LocalDateTime createdAt) {}

    @Select("""
            SELECT token_prefix AS tokenPrefix, tool_name AS toolName, result,
                   src_ip AS srcIp, user_agent AS userAgent,
                   duration_ms AS durationMs, created_at AS createdAt
              FROM ask_access_audit
             WHERE family_id = #{familyId}
             ORDER BY created_at DESC
             LIMIT #{limit}
            """)
    List<Row> recent(@Param("familyId") long familyId, @Param("limit") int limit);

    /**
     * v1.19.15 · 从某个时刻起,<b>百炼那边</b>有没有访问过我们的 MCP 端点。
     *
     * <p>用来回答一个否则完全看不见的问题:「智能体到底连上我的账房了吗」。
     * 连不上时百炼<b>不报错</b> —— 它只是让模型在没有工具的情况下回答,
     * 模型于是说「我这边没有数据查询工具」。用户看到的是一段像模型犯傻的话,
     * 而真因是 MCP 服务不在部署成功状态。</p>
     *
     * <p>判据用 {@code user_agent} 而不是 IP:百炼的出口 IP 会变(实测两个不同网段),
     * 而它的 UA 一直是 {@code Bailian-MCP}。</p>
     */
    @Select("""
            SELECT COUNT(*) FROM ask_access_audit
             WHERE family_id = #{familyId}
               AND created_at >= #{since}
               AND user_agent LIKE '%Bailian%'
            """)
    int countUpstreamCallsSince(@Param("familyId") long familyId,
                                @Param("since") LocalDateTime since);

    /** 换绑进度:该接入点最近是否还在用旧密钥 */
    @Select("""
            SELECT COUNT(*) FROM ask_access_audit
             WHERE token_prefix = #{prefix} AND result = 'OK_NEW'
            """)
    int countNewKeyUsed(@Param("prefix") String prefix);
}
