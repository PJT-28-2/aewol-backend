-- 저금통 내부 이체(수동 넣기/출금/일일 절삭)의 작업 유형.
-- 멱등키 네임스페이스와 함께 써서, 클라이언트가 자동 절삭 키와 같은 값을 보내도
-- 수동 넣기 결과로 오인하거나 유니크 제약에 걸리지 않게 한다.
ALTER TABLE `transaction`
    ADD COLUMN `transfer_purpose` VARCHAR(32) NULL
        COMMENT 'POT_DEPOSIT / POT_WITHDRAW / SPARE_TRIM. 내부 이체 작업 유형'
        AFTER `idempotency_key`;

-- pot-deposit:{clientKey} 접두사(12자) + 클라이언트 키(최대 64자) = 최대 76자.
ALTER TABLE `transaction`
    MODIFY COLUMN `idempotency_key` VARCHAR(80) NULL
        COMMENT '요청 재시도 식별 키. 내부 이체는 작업 유형 접두사를 붙인다';
