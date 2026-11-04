CREATE TABLE IF NOT EXISTS retirement_plan (
    goal_id BIGINT NOT NULL PRIMARY KEY,
    family_id BIGINT NOT NULL,
    plan_json JSON NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_for_plan BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_retirement_goal FOREIGN KEY (goal_id) REFERENCES family_goal(id) ON DELETE CASCADE,
    KEY idx_retirement_family (family_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE goal_ai_report ADD COLUMN context_fingerprint VARCHAR(64) NULL;
