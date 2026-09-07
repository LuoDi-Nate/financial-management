package com.family.finance.repository;

import com.family.finance.domain.group.AccountGroup;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** v1.20 · 账户组与成员关系。单属由 {@code account_group_member} 的主键(account_id)保证,不靠这里。 */
@Mapper
public interface AccountGroupMapper {

    @Insert("""
            INSERT INTO account_group (family_id, name, note, created_by)
            VALUES (#{familyId}, #{name}, #{note}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AccountGroup g);

    @Update("UPDATE account_group SET name = #{name}, note = #{note} WHERE id = #{id} AND family_id = #{familyId}")
    int rename(AccountGroup g);

    /** 删组 = 成员关系随外键级联删掉;账户本身与历史数据一行不动 */
    @Delete("DELETE FROM account_group WHERE id = #{id} AND family_id = #{familyId}")
    int delete(@Param("familyId") long familyId, @Param("id") long id);

    @Select("""
            SELECT id, family_id AS familyId, name, note, created_by AS createdBy, created_at AS createdAt
              FROM account_group WHERE family_id = #{familyId} ORDER BY id
            """)
    List<AccountGroup> findByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT id, family_id AS familyId, name, note, created_by AS createdBy, created_at AS createdAt
              FROM account_group WHERE id = #{id} AND family_id = #{familyId}
            """)
    AccountGroup findById(@Param("familyId") long familyId, @Param("id") long id);

    // ──────────────── 成员关系 ────────────────

    /**
     * 加成员。{@code ON DUPLICATE KEY UPDATE} 是<b>换组</b>语义 ——
     * account_id 是主键,同一账户再加到别的组就是把它<b>搬过去</b>,而不是报错。
     * 这正是「一个账户最多属于一个组」在写入侧的自然表达。
     */
    @Insert("""
            INSERT INTO account_group_member (group_id, account_id) VALUES (#{groupId}, #{accountId})
            ON DUPLICATE KEY UPDATE group_id = VALUES(group_id), added_at = CURRENT_TIMESTAMP(3)
            """)
    int addMember(@Param("groupId") long groupId, @Param("accountId") long accountId);

    @Delete("DELETE FROM account_group_member WHERE account_id = #{accountId}")
    int removeMember(@Param("accountId") long accountId);

    @Delete("DELETE FROM account_group_member WHERE group_id = #{groupId}")
    int clearMembers(@Param("groupId") long groupId);

    record Member(Long groupId, Long accountId) {}

    /** 全家的成员关系一次取回 —— 解析器要的是整张映射,不是逐账户查(防 N+1) */
    @Select("""
            SELECT m.group_id AS groupId, m.account_id AS accountId
              FROM account_group_member m
              JOIN account_group g ON g.id = m.group_id
             WHERE g.family_id = #{familyId}
            """)
    List<Member> findMembersByFamily(@Param("familyId") long familyId);

    @Select("SELECT account_id FROM account_group_member WHERE group_id = #{groupId}")
    List<Long> findAccountIdsByGroup(@Param("groupId") long groupId);
}
