-- v1.35.1 · 顶栏品牌名旁的小图标可以自己选(维护者 2026-10-20:「允许那个存钱罐的小 icon 被用户定制」)
--   存的是 BrandMark 的 code(如 'cat' / 'sprout'),图形在代码里(domain/family/BrandMark.java)。
--   NULL = 默认存钱罐(v1.35.0 的样子)。认不出的 code 同样按存钱罐显示,不报错。
-- 可空新列:老 jar 不读,回滚无需 SQL。
ALTER TABLE family
  ADD COLUMN brand_mark VARCHAR(32) NULL COMMENT 'v1.35.1 · 品牌名旁小图标 code(BrandMark),NULL = 存钱罐';
