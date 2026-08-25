-- PUBLISHING 전이/이미지 공개 사본 예약을 일기 단위로 직렬화하는 락 토큰이다.
-- visibility만으로는 "같은 값으로의 재전이"를 막지 못해 동시 요청 간 소유권을
-- 구분할 수 없었다(리뷰로 발견). publish_token이 발급돼 있는 동안에는 그 값을
-- 아는 요청만 완료/취소할 수 있고, publishing_started_at이 오래됐으면(=프로세스가
-- 죽어 완료도 취소도 못 한 채 멈춘 것으로 보고) 다른 요청이 이어받을 수 있다.
ALTER TABLE `care_diary`
    ADD COLUMN `publish_token` VARCHAR(36) NULL
        COMMENT '공개 전환/복원 작업 소유 토큰. 비어 있으면 진행 중인 작업이 없다' AFTER `visibility`,
    ADD COLUMN `publishing_started_at` DATETIME NULL
        COMMENT 'publish_token을 발급한 시각. 오래되면 죽은 작업으로 보고 회수한다' AFTER `publish_token`;
