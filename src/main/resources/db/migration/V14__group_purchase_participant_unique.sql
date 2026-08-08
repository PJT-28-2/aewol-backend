-- 동시 요청에서 findParticipant 조회 후 insert하는 방식은 원자적이지 않아 레이스 컨디션으로
-- 같은 회원이 같은 공동구매에 중복 참여할 수 있다. (gp_id, member_id) 복합 UNIQUE로 DB 레벨에서 최종 방어한다.
ALTER TABLE `group_purchase_participant`
    ADD CONSTRAINT `uq_gpp_gp_member` UNIQUE (`gp_id`, `member_id`);
