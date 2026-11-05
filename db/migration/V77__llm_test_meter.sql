CREATE TABLE llm_test_meter (
  family_id BIGINT NOT NULL,
  epoch CHAR(36) NOT NULL,
  surface VARCHAR(80) NOT NULL,
  blocked_count BIGINT NOT NULL DEFAULT 0,
  sent_count BIGINT NOT NULL DEFAULT 0,
  last_blocked_at DATETIME(3) NULL,
  last_sent_at DATETIME(3) NULL,
  PRIMARY KEY (family_id, epoch, surface),
  CONSTRAINT fk_llm_test_meter_family FOREIGN KEY (family_id) REFERENCES family(id) ON DELETE CASCADE
);
