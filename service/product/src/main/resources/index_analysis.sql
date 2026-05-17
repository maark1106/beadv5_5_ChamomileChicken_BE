-- ============================================================
-- 인덱스 단계별 분석 스크립트
-- 순서대로 실행하며 EXPLAIN 결과 비교
-- ============================================================

-- ■ STEP 0: 현재 인덱스 확인
SHOW INDEX FROM products;

-- ■ STEP 0: BASELINE - 인덱스 없음
-- type=ALL, rows≈5,000,000, Extra=Using filesort 확인
EXPLAIN SELECT id, title, price, thumbnail_path, category, region, reg_dt
FROM products
WHERE category = 'SPORTS'
  AND region = 'GANGNAM'
  AND status = 'ENABLE'
  AND delete_dt IS NULL
ORDER BY reg_dt DESC
LIMIT 10;

-- ============================================================
-- ■ STEP 1: 단일 인덱스 각각 추가 → index merge 확인
-- ============================================================
CREATE INDEX idx_category ON products (category);
CREATE INDEX idx_region   ON products (region);

-- index merge 발생 확인
-- type=index_merge, Extra=Using intersect(idx_category, idx_region); Using filesort
EXPLAIN SELECT id, title, price, thumbnail_path, category, region, reg_dt
FROM products
WHERE category = 'SPORTS'
  AND region = 'GANGNAM'
  AND status = 'ENABLE'
  AND delete_dt IS NULL
ORDER BY reg_dt DESC
LIMIT 10;

-- 인덱스 크기 확인
SELECT
    index_name,
    ROUND(stat_value * @@innodb_page_size / 1024 / 1024, 2) AS size_mb
FROM mysql.innodb_index_stats
WHERE database_name = DATABASE()
  AND table_name = 'products'
  AND stat_name = 'size'
ORDER BY index_name;

-- 단일 인덱스 제거
DROP INDEX idx_category ON products;
DROP INDEX idx_region   ON products;

-- ============================================================
-- ■ STEP 2: 복합 인덱스 추가 → filesort 제거 확인
-- ============================================================
-- category 먼저: 선택도 20%(5개) > region 선택도 4%(25개) 이므로
-- region이 선택도 더 높아서 앞에 와야 하지 않나? 라는 질문 대비:
-- → WHERE 절에서 동치(=) 조건은 둘 다 같은 레벨, 이후 ORDER BY reg_dt가 핵심
-- → region을 앞에 두면: (region, category, status, delete_dt, reg_dt) 도 가능
-- → 실제 EXPLAIN rows 비교로 결론 도출

CREATE INDEX idx_category_region ON products (category, region, status, delete_dt, reg_dt);

-- type=ref, rows 대폭 감소, Extra에서 Using filesort 사라짐 확인
EXPLAIN SELECT id, title, price, thumbnail_path, category, region, reg_dt
FROM products
WHERE category = 'SPORTS'
  AND region = 'GANGNAM'
  AND status = 'ENABLE'
  AND delete_dt IS NULL
ORDER BY reg_dt DESC
LIMIT 10;

-- 인덱스 크기 비교
SELECT
    index_name,
    ROUND(stat_value * @@innodb_page_size / 1024 / 1024, 2) AS size_mb
FROM mysql.innodb_index_stats
WHERE database_name = DATABASE()
  AND table_name = 'products'
  AND stat_name = 'size'
ORDER BY index_name;

-- ============================================================
-- ■ STEP 3: 커버링 인덱스 → heap lookup 제거
-- ============================================================
DROP INDEX idx_category_region ON products;

-- SELECT 컬럼(id, title, price, thumbnail_path)까지 인덱스에 포함
CREATE INDEX idx_covering ON products (category, region, status, delete_dt, reg_dt, id, title, price, thumbnail_path);

-- Extra=Using index 확인 (heap lookup 없음)
EXPLAIN SELECT id, title, price, thumbnail_path, category, region, reg_dt
FROM products
WHERE category = 'SPORTS'
  AND region = 'GANGNAM'
  AND status = 'ENABLE'
  AND delete_dt IS NULL
ORDER BY reg_dt DESC
LIMIT 10;

-- 인덱스 크기 비교 (복합 인덱스보다 커진 것 확인 → 트레이드오프)
SELECT
    index_name,
    ROUND(stat_value * @@innodb_page_size / 1024 / 1024, 2) AS size_mb
FROM mysql.innodb_index_stats
WHERE database_name = DATABASE()
  AND table_name = 'products'
  AND stat_name = 'size'
ORDER BY index_name;

-- ============================================================
-- ■ STEP 4: 쓰기 성능 트레이드오프 측정
-- ============================================================
-- 인덱스 없을 때 INSERT 100건 시간 측정
SET @start = NOW(6);
INSERT INTO products (id, seller_id, title, max_capacity, description, description_path,
                      price, status, road_address, zonecode, latitude, longitude, category, region, reg_dt, modify_dt)
SELECT UUID_TO_BIN(UUID(), 1), UUID_TO_BIN(UUID(), 1),
       CONCAT('WRITE_TEST_', seq), 10, '설명', '[]',
       50000, 'ENABLE', '서울특별시', '00000', 37.5, 126.9,
       ELT((seq MOD 5) + 1, 'SPORTS', 'COOKING', 'ART', 'BEAUTY', 'OTHER'),
       ELT((seq MOD 25) + 1, 'GANGNAM', 'GANGDONG', 'GANGBUK', 'GANGSEO', 'GWANAK',
           'GWANGJIN', 'GURO', 'GEUMCHEON', 'NOWON', 'DOBONG',
           'DONGDAEMUN', 'DONGJAK', 'MAPO', 'SEODAEMUN', 'SEOCHO',
           'SEONGDONG', 'SEONGBUK', 'SONGPA', 'YANGCHEON', 'YEONGDEUNGPO',
           'YONGSAN', 'EUNPYEONG', 'JONGNO', 'JUNG', 'JUNGNANG'),
       NOW(), NOW()
FROM (SELECT seq FROM (
    SELECT 0 AS seq UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
    UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9
) t1, (
    SELECT 0 AS mul UNION SELECT 10 UNION SELECT 20 UNION SELECT 30 UNION SELECT 40
    UNION SELECT 50 UNION SELECT 60 UNION SELECT 70 UNION SELECT 80 UNION SELECT 90
) t2 WHERE t1.seq + t2.mul < 100) AS seqs;
SELECT TIMESTAMPDIFF(MICROSECOND, @start, NOW(6)) / 1000 AS insert_100_ms;

-- 테이블 + 인덱스 전체 크기
SELECT
    table_name,
    ROUND(data_length / 1024 / 1024, 2)  AS data_mb,
    ROUND(index_length / 1024 / 1024, 2) AS index_mb
FROM information_schema.TABLES
WHERE table_schema = DATABASE()
  AND table_name = 'products';