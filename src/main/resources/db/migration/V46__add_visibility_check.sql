-- V46: visibility 값 제약과 신고 이력 보존
--
-- 리뷰 지적 반영이다.
--
-- 1) visibility가 VARCHAR라 'public'(소문자)이나 'HIDDEN' 같은 값이 들어갈 수 있었다.
--    피드 SQL은 그런 행을 조용히 빼버려 원인을 찾기 어렵다. CHECK로 막는다.
--
-- 2) diary_report의 일기 FK가 ON DELETE CASCADE였다. 신고는 감사 성격이라 대상 글이
--    지워졌다고 함께 사라지면 곤란하다. care_diary는 soft delete를 쓰므로 실제 삭제가
--    드물지만, 정책을 분명히 해 둔다.
--
-- visibility와 hidden_by_report_at의 조합에는 CHECK를 걸지 않는다. 신고로 내려간 글은
-- visibility='PUBLIC' + hidden_by_report_at IS NOT NULL 상태로 남는 것이 의도다.
-- visibility는 작성자의 뜻이고 hidden_by_report_at은 운영 판단이라, 둘을 한 값으로
-- 합치면 신고가 풀렸을 때 작성자가 원래 공개를 원했는지 알 수 없다. 노출 차단은 공개
-- 조회 쿼리(publicOnly)와 공개 전환 UPDATE의 조건이 함께 보장한다.

ALTER TABLE `care_diary`
    ADD CONSTRAINT `ck_care_diary_visibility`
    CHECK (`visibility` IN ('PUBLIC', 'PRIVATE'));

ALTER TABLE `diary_report`
    DROP FOREIGN KEY `fk_diary_report_diary`;

ALTER TABLE `diary_report`
    ADD CONSTRAINT `fk_diary_report_diary`
    FOREIGN KEY (`diary_id`) REFERENCES `care_diary` (`diary_id`);
