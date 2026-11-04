-- 老 jar 不认识原生退休生活账;保留计划与目标,暂时归档,重新升级后可从目标管理恢复。
-- 由旧目标采用新方案的记录不改:旧参数从未被覆盖,老 jar 自然使用原算法。
UPDATE family_goal g
JOIN retirement_plan r ON r.goal_id = g.id AND r.family_id = g.family_id
SET g.archived_at = CURRENT_TIMESTAMP(3)
WHERE r.created_for_plan = TRUE AND g.archived_at IS NULL;
