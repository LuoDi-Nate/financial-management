ALTER TABLE family_goal
  ADD COLUMN forecast_source VARCHAR(12) NULL,
  ADD COLUMN forecast_manual_amount DECIMAL(19,2) NULL,
  ADD COLUMN forecast_manual_currency CHAR(3) NULL,
  ADD COLUMN forecast_wish_month CHAR(7) NULL,
  ADD COLUMN forecast_wish_override BOOLEAN NOT NULL DEFAULT FALSE,
  ADD CONSTRAINT ck_goal_forecast_source CHECK (forecast_source IS NULL OR forecast_source IN ('AUTO','MANUAL')),
  ADD CONSTRAINT ck_goal_forecast_amount_currency CHECK
    ((forecast_manual_amount IS NULL AND forecast_manual_currency IS NULL)
     OR (forecast_manual_amount IS NOT NULL AND forecast_manual_currency IS NOT NULL)),
  ADD CONSTRAINT ck_goal_forecast_manual CHECK
    (forecast_source IS NULL OR forecast_source <> 'MANUAL' OR forecast_manual_amount IS NOT NULL);
