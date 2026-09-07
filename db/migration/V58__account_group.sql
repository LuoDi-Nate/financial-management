-- =====================================================================
-- v1.20 · 账户组
--
-- 三张【全新】表,不改任何既有表 —— 老代码在新库上完全正常,回滚后空表无副作用。
--
-- 设计要点(详见 tech-design/v1.20.md §二):
--
--   · 单属靠 **DB 唯一索引** 保证,不靠应用层校验。
--     理由:归因有恒等式「基准 + 人赚 + 钱赚 + 开账基线 = 本期净变化」,
--     一个账户进两个组会双计,而差额会被【静默吸进「未归因」】——
--     数字看着平了,错误藏起来了。这类失败必须在存储层拦。
--
--   · 组不是账户的属性,所以 account 表【一列不加】。
--     账户默认不属于任何组;没建组时所有页面与 v1.19.16 逐字一致。
--
--   · period_account_group 是【该期定格】,与 period_account_attr 同一条规则:
--     关账时写入、重开时删除,于是「重开后再关账 = 重新定格」是结构上必然的。
--     没有它,今天改一次组成员,12 期趋势图会全变 —— 用户会当成算错了。
--
--   · group_name 冗余进定格表是刻意的:组被改名或删除之后,
--     历史仍然显示当时的名字。与 period_account_attr 冗余 product_category_name 同理。
-- =====================================================================

CREATE TABLE IF NOT EXISTS account_group (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    family_id   BIGINT       NOT NULL,
    name        VARCHAR(40)  NOT NULL,
    note        VARCHAR(200)     NULL,
    created_by  BIGINT           NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_family_name (family_id, name),
    KEY idx_group_family (family_id),
    CONSTRAINT fk_group_family FOREIGN KEY (family_id) REFERENCES family (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.20 账户组(可选的叠加聚合层)';

CREATE TABLE IF NOT EXISTS account_group_member (
    group_id    BIGINT      NOT NULL,
    account_id  BIGINT      NOT NULL,
    added_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    -- 【本迁移最重要的一行】account_id 单独唯一 = 一个账户最多属于一个组。
    -- 不是 (group_id, account_id) 联合唯一 —— 那样同一账户还能进多个组。
    PRIMARY KEY (account_id),
    KEY idx_member_group (group_id),
    CONSTRAINT fk_member_group   FOREIGN KEY (group_id)   REFERENCES account_group (id) ON DELETE CASCADE,
    CONSTRAINT fk_member_account FOREIGN KEY (account_id) REFERENCES account (id)       ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.20 组成员(account_id 为主键 = 单属)';

CREATE TABLE IF NOT EXISTS period_account_group (
    period_id   BIGINT      NOT NULL,
    account_id  BIGINT      NOT NULL,
    group_id    BIGINT      NOT NULL,
    group_name  VARCHAR(40) NOT NULL,
    sealed_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (period_id, account_id),
    KEY idx_pag_period_group (period_id, group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v1.20 该期分组定格(关账写入 · 重开删除)';
