// Isolated synthetic tables only: no application table or schema_history is modified.
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const {randomBytes} = require('node:crypto');
const tables = ['llm_prompt_record','review_ai_cache','rebalance_advice_cache','goal_ai_report','holding_import','ask_message'];
if (!process.env.DB_NAME || !process.env.DB_USER) throw new Error('DB_NAME and DB_USER are required');
const prefix = 'qa_v142_' + randomBytes(6).toString('hex') + '_';
const names = tables.map(t => prefix + t);
const args = ['--batch','--skip-column-names','-h',process.env.DB_HOST || '127.0.0.1','-P',process.env.DB_PORT || '3306','-u',process.env.DB_USER,process.env.DB_NAME];
const env = {...process.env, MYSQL_PWD: process.env.DB_PASS || process.env.MYSQL_PWD || ''};
function sql(text, failure = false) {
  const r = spawnSync('mysql',args,{input:text,encoding:'utf8',env});
  if (failure) { if (r.status === 0) throw new Error('Incompatible column was silently accepted'); return; }
  if (r.status !== 0) throw new Error('Synthetic migration check failed: ' + r.stderr);
  return r.stdout.trim();
}
let migration = fs.readFileSync(path.resolve(__dirname,'../db/migration/V81__custom_llm_source.sql'),'utf8');
for (const table of tables) migration = migration.replaceAll(table,prefix + table);
let passes = 0;
function verify() {
  const count = sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN (" + names.map(n => "'"+n+"'").join(',') + ") AND COLUMN_NAME='source_json' AND DATA_TYPE='json' AND IS_NULLABLE='YES';");
  if (count !== '6') throw new Error('Missing or incompatible source columns');
  for (const n of names) if (sql('SELECT keep_value FROM `'+n+'` WHERE id=1 AND source_json IS NULL;') !== 'synthetic') throw new Error('Original row changed');
  passes++;
}
function create() {
  for (const n of names) sql('CREATE TABLE `'+n+'` (id INT PRIMARY KEY, keep_value VARCHAR(16)); INSERT INTO `'+n+'` VALUES (1,\'synthetic\');');
}
function cleanup() { for (const n of names) sql('DROP TABLE IF EXISTS `'+n+'`;'); }
try {
  create(); sql(migration); verify(); sql(migration); verify();
  const chunks = migration.trim().split(/\n\s*\n/);
  if (chunks.length !== 6) throw new Error('Migration steps must be reviewed before changing this experiment');
  for (let stop = 1; stop < 6; stop++) {
    cleanup(); create(); sql(chunks.slice(0,stop).join('\n\n')); sql(migration); verify();
  }
  cleanup(); create(); sql('ALTER TABLE `'+names[3]+'` ADD COLUMN source_json VARCHAR(64) NULL;');
  sql(migration,true); passes++;
  sql('ALTER TABLE `'+names[3]+'` MODIFY COLUMN source_json JSON NULL;'); sql(migration); verify();
  sql("SET SESSION sql_mode=CONCAT(@@sql_mode,',ANSI_QUOTES');\n"+migration); verify();
} finally { cleanup(); }
console.log('PASS ' + passes + ' migration scenarios; fixture tables removed; application tables unchanged');
