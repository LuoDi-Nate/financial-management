-- Only nullable metadata. Every step tolerates a previously completed DDL after interruption.
-- An existing incompatible column deliberately fails JSON parsing; never silently accept it.
SET @source_ddl = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE llm_prompt_record ADD COLUMN source_json JSON NULL',
 IF(SUM(DATA_TYPE='json' AND IS_NULLABLE='YES')=1,'DO 0','SELECT CAST(''source_json_schema_mismatch'' AS JSON)'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='llm_prompt_record' AND COLUMN_NAME='source_json');
PREPARE source_stmt FROM @source_ddl;
EXECUTE source_stmt;
DEALLOCATE PREPARE source_stmt;

SET @source_ddl = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE review_ai_cache ADD COLUMN source_json JSON NULL',
 IF(SUM(DATA_TYPE='json' AND IS_NULLABLE='YES')=1,'DO 0','SELECT CAST(''source_json_schema_mismatch'' AS JSON)'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='review_ai_cache' AND COLUMN_NAME='source_json');
PREPARE source_stmt FROM @source_ddl;
EXECUTE source_stmt;
DEALLOCATE PREPARE source_stmt;

SET @source_ddl = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE rebalance_advice_cache ADD COLUMN source_json JSON NULL',
 IF(SUM(DATA_TYPE='json' AND IS_NULLABLE='YES')=1,'DO 0','SELECT CAST(''source_json_schema_mismatch'' AS JSON)'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='rebalance_advice_cache' AND COLUMN_NAME='source_json');
PREPARE source_stmt FROM @source_ddl;
EXECUTE source_stmt;
DEALLOCATE PREPARE source_stmt;

SET @source_ddl = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE goal_ai_report ADD COLUMN source_json JSON NULL',
 IF(SUM(DATA_TYPE='json' AND IS_NULLABLE='YES')=1,'DO 0','SELECT CAST(''source_json_schema_mismatch'' AS JSON)'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='goal_ai_report' AND COLUMN_NAME='source_json');
PREPARE source_stmt FROM @source_ddl;
EXECUTE source_stmt;
DEALLOCATE PREPARE source_stmt;

SET @source_ddl = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE holding_import ADD COLUMN source_json JSON NULL',
 IF(SUM(DATA_TYPE='json' AND IS_NULLABLE='YES')=1,'DO 0','SELECT CAST(''source_json_schema_mismatch'' AS JSON)'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='holding_import' AND COLUMN_NAME='source_json');
PREPARE source_stmt FROM @source_ddl;
EXECUTE source_stmt;
DEALLOCATE PREPARE source_stmt;

SET @source_ddl = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE ask_message ADD COLUMN source_json JSON NULL',
 IF(SUM(DATA_TYPE='json' AND IS_NULLABLE='YES')=1,'DO 0','SELECT CAST(''source_json_schema_mismatch'' AS JSON)'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ask_message' AND COLUMN_NAME='source_json');
PREPARE source_stmt FROM @source_ddl;
EXECUTE source_stmt;
DEALLOCATE PREPARE source_stmt;
