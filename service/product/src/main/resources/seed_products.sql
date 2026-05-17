-- ============================================================
-- 상품 테스트 데이터 시드 스크립트
-- category 5개 × region 25개 = 125 조합 × 40,000건 = 500만건
-- UUID: UUID_TO_BIN(UUID(), 1) → 시간 정렬 (UUID v7 효과)
-- ============================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS seed_products;

DELIMITER //

CREATE PROCEDURE seed_products()
BEGIN
    DECLARE i       INT DEFAULT 0;
    DECLARE total   INT DEFAULT 5000000;
    DECLARE cat_idx INT;
    DECLARE reg_idx INT;

    SET autocommit = 0;
    SET unique_checks = 0;
    SET foreign_key_checks = 0;

    WHILE i < total DO
        -- 125 조합 균일 분포
        -- category: (i DIV 25) MOD 5 → 40,000건마다 변경
        -- region:   i MOD 25         → 25건마다 순환
        SET cat_idx = ((i DIV 25) MOD 5) + 1;
        SET reg_idx = (i MOD 25) + 1;

        INSERT INTO products (
            id, seller_id,
            title, max_capacity, description, description_path,
            price, status,
            road_address, detail_address, zonecode,
            latitude, longitude,
            category, region,
            reg_dt, modify_dt, delete_dt
        ) VALUES (
            UUID_TO_BIN(UUID(), 1),
            UUID_TO_BIN(UUID(), 1),

            CONCAT(ELT(cat_idx, 'SPORTS', 'COOKING', 'ART', 'BEAUTY', 'OTHER'), '_클래스_', i),
            10,
            '클래스 설명입니다.',
            '[]',

            FLOOR(10000 + RAND() * 190000),
            'ENABLE',

            '서울특별시',
            NULL,
            '00000',
            37.5665 + (RAND() - 0.5) * 0.1,
            126.9780 + (RAND() - 0.5) * 0.1,

            ELT(cat_idx, 'SPORTS', 'COOKING', 'ART', 'BEAUTY', 'OTHER'),

            ELT(reg_idx,
                'GANGNAM',    'GANGDONG',  'GANGBUK',     'GANGSEO',     'GWANAK',
                'GWANGJIN',   'GURO',      'GEUMCHEON',   'NOWON',       'DOBONG',
                'DONGDAEMUN', 'DONGJAK',   'MAPO',        'SEODAEMUN',   'SEOCHO',
                'SEONGDONG',  'SEONGBUK',  'SONGPA',      'YANGCHEON',   'YEONGDEUNGPO',
                'YONGSAN',    'EUNPYEONG', 'JONGNO',      'JUNG',        'JUNGNANG'
            ),

            NOW() - INTERVAL FLOOR(RAND() * 365) DAY,
            NOW(),
            NULL
        );

        SET i = i + 1;

        IF i MOD 10000 = 0 THEN
            COMMIT;
        END IF;
    END WHILE;

    COMMIT;
    SET autocommit = 1;
    SET unique_checks = 1;
    SET foreign_key_checks = 1;
END //

DELIMITER ;

CALL seed_products();
DROP PROCEDURE IF EXISTS seed_products;

-- 조합별 건수 확인 (각 40,000건인지 검증)
SELECT category, region, COUNT(*) AS cnt
FROM products
GROUP BY category, region
ORDER BY category, region;