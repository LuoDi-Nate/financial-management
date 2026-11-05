ALTER TABLE macro_benchmark ADD COLUMN source_url VARCHAR(500) NULL,
  ADD COLUMN verified_at DATETIME(3) NULL;

-- Public NBS annual CPI releases. Only untouched, known seed values are replaced.
-- Manual corrections and unexpected seed values are left intact.
UPDATE macro_benchmark b JOIN (
 SELECT 2016 AS year, 2.00 AS expected, 2.00 AS actual, 'https://www.stats.gov.cn/sj/sjjd/202302/t20230202_1895811.html' AS url
 UNION ALL SELECT 2017,1.60,1.60,'https://www.stats.gov.cn/sj/zxfb/202302/t20230203_1899855.html'
 UNION ALL SELECT 2018,2.10,2.10,'https://www.stats.gov.cn/xxgk/sjfb/tjgb2020/201902/t20190228_1768642.html'
 UNION ALL SELECT 2019,2.90,2.90,'https://www.stats.gov.cn/sj/zxfb/202302/t20230203_1900640.html'
 UNION ALL SELECT 2020,2.50,2.50,'https://www.stats.gov.cn/sj/zxfb/202302/t20230203_1901004.html'
 UNION ALL SELECT 2021,0.90,0.90,'https://www.stats.gov.cn/sj/zxfb/202302/t20230203_1901393.html'
 UNION ALL SELECT 2022,2.00,2.00,'https://www.stats.gov.cn/sj/zxfb/202302/t20230228_1919011.html'
 UNION ALL SELECT 2023,0.20,0.20,'https://www.stats.gov.cn/xxgk/sjfb/zxfb2020/202401/t20240112_1946471.html'
 UNION ALL SELECT 2024,0.20,0.20,'https://www.stats.gov.cn/sj/zxfb/202502/t20250228_1958817.html'
 UNION ALL SELECT 2025,0.30,0.00,'https://www.stats.gov.cn/sj/zxfb/202602/t20260228_1962662.html'
) v ON b.year=v.year AND b.cpi_headline=v.expected
SET b.cpi_headline=v.actual, b.source='official-nbs', b.source_url=v.url, b.verified_at=CURRENT_TIMESTAMP(3)
WHERE b.source='seed';

UPDATE macro_benchmark SET verified_at=fetched_at WHERE source='manual' AND verified_at IS NULL;
