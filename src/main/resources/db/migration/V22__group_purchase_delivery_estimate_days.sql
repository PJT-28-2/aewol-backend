-- 공동구매 배송예정일을 관리자가 입력한 고정 날짜가 아니라 "예상 소요일" 기준으로 관리한다.
-- delivery_date는 등록 시 deadline + delivery_estimate_days(잠정치)로 채워지고,
-- 목표 수량을 달성하는 순간(마감 전 조기 확정 포함) confirmedDate + delivery_estimate_days로 갱신된다.
-- delivery_date 컬럼 자체는 그대로 두고 값의 의미만 "고정 입력값"에서 "계산된 결과값"으로 바뀐다.
ALTER TABLE `group_purchase`
    ADD COLUMN `delivery_estimate_days` INT NULL COMMENT '배송 예상 소요일(목표 달성일 기준)';
