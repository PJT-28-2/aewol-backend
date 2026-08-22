-- V49: target_quantity > 0을 DB 레벨에서 강제한다.
--
-- V48부터 group_purchase.is_urgent_active는 INSERT 시점에 1로 하드코딩된다(GroupPurchaseMapper.xml
-- insert). 그 근거는 "생성 시점엔 deadline이 항상 미래(@Future)이고 current_quantity=0이라
-- current_quantity < target_quantity가 항상 참"인데, 이건 target_quantity > 0을 전제로 한다.
-- 지금까지는 GroupPurchaseCreateRequest의 @Min(1) 애플리케이션 검증에만 의존했다 — 이 검증이
-- 미래에 실수로 완화되면 target_quantity <= 0인 행이 is_urgent_active=1로 영구히 잘못 박힌 채
-- 남는다(예전의 CASE WHEN 방식은 매 조회마다 재계산해 자동으로 바로잡혔지만, 컬럼에 값을
-- 저장해두는 지금 방식은 참여/취소/자정 배치 어느 것도 이 케이스를 고쳐주지 않는다).
--
-- 적용 전 확인: 2026-08-23 기준 운영/개발 DB에 target_quantity <= 0인 행 0건(코드 주석의
-- "레거시/비정상 데이터"는 현재 존재하지 않음) — 제약을 걸어도 기존 데이터가 깨지지 않는다.
ALTER TABLE `group_purchase`
    ADD CONSTRAINT `chk_gp_target_quantity_positive` CHECK (`target_quantity` > 0);
