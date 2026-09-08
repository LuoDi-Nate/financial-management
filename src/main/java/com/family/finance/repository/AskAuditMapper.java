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

    /**
     * v1.20.1 · 最近的<b>入站</b>访问记录,给管理页直接看。
     *
     * <p>加它的理由是一次长时间的瞎猜:线上智能体说「我这边没有数据查询工具」,
     * 而我们<b>没有任何地方能看出百炼到底有没有来过</b> ——
     * 于是只能反复推测「大概是 MCP 服务没部署成功」,把查证推给用户去控制台翻。
     *
     * <p>其实答案一直躺在这张表里:<b>来过但鉴权失败</b>(有记录、result=INVALID)
     * 与<b>根本没来过</b>(一条记录都没有)是两个完全不同的病因,
     * 而它们在页面上长得一模一样 —— 都是「智能体说没有工具」。
     *
     * <p>不按 UA 过滤:要能看见「来了、但 UA 不是百炼」这种情况。
     */
    @Select("""
            SELECT created_at, token_prefix, tool_name, result, src_ip, user_agent
              FROM ask_access_audit
             WHERE family_id IN (#{familyId}, 0)
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<InboundRow> recentInbound(@Param("familyId") long familyId, @Param("limit") int limit);

    /**
     * v1.20.2 · 百炼<b>最后一次</b>成功访问是什么时候。
     *
     * <p>「去控制台看看服务状态」这种提示没法让人动手,因为它不告诉你<b>什么时候坏的</b>。
     * 而「上次成功是 09-03 15:24,之后再没有」立刻就能对上「我那天改了什么」——
     * 这一条把一个无从下手的结论变成一个可查的线索。</p>
     */
    @Select("""
            SELECT MAX(created_at) FROM ask_access_audit
             WHERE family_id = #{familyId}
               AND result = 'OK'
               AND user_agent LIKE '%Bailian%'
            """)
    LocalDateTime lastBailianOkAt(@Param("familyId") long familyId);

    /** 一条入站记录(family_id=0 表示鉴权就没过,认不出是哪一家) */
    record InboundRow(LocalDateTime createdAt, String tokenPrefix, String toolName,
                      String result, String srcIp, String userAgent) {

        /** 本机 curl 联调 vs 真的从外面来的 —— 这一栏是判断「百炼来没来过」的关键 */
        public boolean fromOutside() {
            if (srcIp == null || srcIp.isBlank()) return false;
            return !(srcIp.startsWith("127.") || srcIp.equals("::1")
                     || srcIp.equals("0:0:0:0:0:0:0:1") || srcIp.startsWith("192.168.")
                     || srcIp.startsWith("10.") || srcIp.equals("localhost"));
        }

        public boolean looksBailian() {
            return userAgent != null && userAgent.toLowerCase().contains("bailian");
        }
    }

    /** 换绑进度:该接入点最近是否还在用旧密钥 */
    @Select("""
            SELECT COUNT(*) FROM ask_access_audit
             WHERE token_prefix = #{prefix} AND result = 'OK_NEW'
            """)
    int countNewKeyUsed(@Param("prefix") String prefix);
}
