-- v1.34 · issue #40 · 账户里的持仓可以自己排顺序
--   NULL = 没排过:按添加先后(id)—— 和改版前完全一样;
--   排过的按 sort_order 升序;之后新加的(NULL)排在最后。
--   只是展示顺序,不进任何金额计算。老 jar 不读这一列,回滚无需 SQL。
ALTER TABLE stock_holding
  ADD COLUMN sort_order INT NULL COMMENT 'v1.34 · 账户内持仓的展示顺序(NULL = 按添加先后,排在排过的后面)';
