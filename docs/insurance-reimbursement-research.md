# 펫보험 환급률·자기부담금 리서치 (S1)

> 계획서: `.omc/plans/pet-insurance-simulator-fix-consensus.md` (v3.1) S1/S2 절 참조.
> 담당: worker-2 (파일럿 단계). **이 문서는 소스가 아니라 산출물이다 — 소스 코드/마이그레이션은 이 문서에서 절대 수정하지 않는다.**
>
> **원칙 (계획서 Principle 1 그대로):** 추측값을 확정값으로 저장하지 않는다. 근거를 못 찾은 수치는 빈칸 + `confidence=UNVERIFIED`로 남긴다. 빈칸이 틀린 값보다 낫다.
> **주의:** 원본의 `%` 문자열 대부분은 **후유장해 지급률**(예: `상해 후유장해(80%이상)`)이지 환급률이 아니다. 담보명 문맥을 반드시 확인하고 인용한다.

## ⚙️ 이 표는 마이그레이션 SQL의 소스다 — 손으로 SQL을 쓰지 말 것

아래 표를 고치면 `V28__insurance_reimbursement_and_category.sql`의 백필 SQL을 **재생성**한다.

```bash
cd aewol-backend
node scripts/gen-insurance-seed.mjs           # 미리보기 (파일 미변경)
node scripts/gen-insurance-seed.mjs --write   # V28의 BEGIN/END GENERATED 구간 치환
```

의존성 없음(Node 표준 라이브러리만, `npm install` 불필요). 재실행해도 같은 결과가 나온다(멱등).

**생성기가 표를 해석하는 규칙:**

| 표의 값 | SQL 결과 |
|---|---|
| 환급률 `50%` | `reimbursement_rate_pct = 50` |
| 환급률 `항암 70% / MRI·CT 50%` (퍼센트 2개 이상) | 대표값 = **최저치 50** + 원문을 `reimbursement_rate_note`로 (계획서 Decision 1) |
| 환급률 빈칸 | `NULL` + `confidence = UNVERIFIED` |
| 자기부담금 `30,000원` | `deductible_krw = 30000` |
| 담보별 차이 노트에 `LIABILITY_TRAVEL` 포함 | 견적 기준 티어를 **만들지 않음** (손익분기 대상 아님) |
| 원본 product_url이 `...`로 축약됨 | DB 기존 값 유지 (덮어쓰지 않음) |
| 원본 product_url이 `'ㅡ'`/스킴 없음 | UPDATE 제외 + **경고 출력** (AC-3 미해결) |

생성기는 무결성 쿼리 1·9 위반과 환급률 80%(후유장해 오추출 의심)를 **SQL 생성 전에 경고로 잡는다.** 경고가 뜨면 표를 고친 뒤 재생성한다.

**`age_subject_confidence`는 이 표에 열이 없어 전 행 `ASSUMED`로 나간다.** `age_basis` 근거를 약관에서 확인한 적이 없으므로 이것이 정직한 값이다. 약관으로 확인한 상품이 생기면 표에 열을 추가하고 생성기를 그에 맞게 고칠 것.

> ⚠️ **Flyway 체크섬:** V28은 **DB에 적용되기 전까지만** 재생성할 수 있다. 한 번 `flywayMigrate`가 돌면 체크섬이 기록되어 이후 파일이 바뀌면 검증에 실패한다. 적용 후 새 근거가 나오면 V28을 고치지 말고 **V30을 새로 만든다.**

## 상태 (파일럿 단계 — 2026-08-12)

- 24개 상품 스캐폴드: **완료** (V3 마이그레이션 INSERT 원문 기준)
- 원본 엑셀 확정 5건: **완료**
- 파일럿 리서치: **2건 수행, 2건 모두 UNVERIFIED로 종결** (근거: 아래 "파일럿 리서치 로그" 참조)
- 나머지 17건: **미착수** — 리드 지시 대기 (지시서 4단계 "여기서 멈추세요" 준수)
- S2(마리당 연 의료비/연 진료 횟수 통계): **본 파일럿 범위 밖** — 별도 작업자 배정 필요

---

## 1. 상품 스캐폴드 (24건, V3 마이그레이션 원문 기준)

출처: `aewol-backend/src/main/resources/db/migration/V3__pk_to_long.sql` `insurance_product` INSERT (94~211행).
`product_id`는 INSERT 순서(1~24)를 그대로 따른다 (마이그레이션 주석 근거).

값 형식 안내: `환급률`/`자기부담금 금액`/`자기부담금 기준`/`자기부담금 적용순서`/`연간한도`/`리서치 출처 URL`은 **채워지지 않은 상품은 빈칸**이다. `confidence`는 `CONFIRMED_OWN_COVERAGE_NAME`(원본 엑셀 보장명에서 직접 확인) / `CONFIRMED_RESEARCH`(외부 약관·상품페이지에서 확인) / `UNVERIFIED`(미확인) 중 하나다.

### DOG (12건)

| # | 회사명 | 상품명 | 월납환산보험료 | 가입연령 | age_basis | 원본 product_url | 환급률 | 자기부담금 | 자기부담금 기준 | 적용순서 | 연간한도 | 리서치 출처 URL | confidence | 담보별 차이 노트 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 삼성화재 | 무배당 삼성화재 다이렉트 착한펫보험(강아지)(2601.6)(자동갱신형) | 6,193 | 0~80 | OWNER | http://direct.samsungfire.com/CR_MyAnycarWeb/overture_index.jsp?OTK=PE2210OB100 | **50%** | | | | | 원본 엑셀 `Desktop/펫보험/` | **CONFIRMED_OWN_COVERAGE_NAME** | 원본 엑셀 보장명에 `(50%보상형)` 명시 |
| 2 | DB손보 | (무)다이렉트 펫블리 반려견보험2607(CM) | 29,921 | 0~80 | OWNER | https://www.directdb.co.kr/ltm/prd/cpnm/custInfoView.do?searchPdcCd=31054&searchPdcTrtHistCd=00&pdcDvcd=l_pet_dog | | | | | | | UNVERIFIED | 미착수 |
| 3 | 농협손보 | (무) NH다이렉트펫앤미든든보험[1종:강아지]2604 | 33,801 | 0~80 | OWNER | https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314512 | | | | | | | UNVERIFIED | **파일럿 대상 — 아래 "파일럿 리서치 로그 #1" 참조. 보상비율 50%/70%·자부담 3만/5만원 "선택형" 정보 발견했으나 이 상품코드(D314512)의 실제 선택 조합 특정 불가로 미확정** |
| 4 | 농협손보 | (무) NH다이렉트펫앤미든든보험[3종:재가입용]2604 | 33,801 | 0~80 | OWNER | https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314512 | | | | | | | UNVERIFIED | 미착수 |
| 5 | 메리츠화재 | (무) 펫퍼민트 Puppy&Family보험 다이렉트2604 | 33,971 | 18~80 | OWNER | https://store.meritzfire.com/pet/product.do#!/ | **70%** | **30,000원** | | | | 원본 엑셀 `Desktop/펫보험/` | **CONFIRMED_OWN_COVERAGE_NAME** | 원본 엑셀 비고 `70%보장형 자기부담금 3만원` 명시. 자기부담금 기준(건당/연간)·적용순서는 원본에 없어 미확인 |
| 6 | DB손보 | (무)다이렉트 펫블리 반려견 의료비보험2605(CM)(재가입용) | 35,727 | 1~19 | PET | https://www.directdb.co.kr/ltm/prd/cpnm/custInfoView.do?searchPdcCd=31054&searchPdcTrtHistCd=00&pdcDvcd=l_pet_dog | **항암 70% / MRI·CT 50%** | | | | | 원본 엑셀 `Desktop/펫보험/` | **CONFIRMED_OWN_COVERAGE_NAME** | 원본 엑셀 보장명 `(70%보상Ⅱ)`(항암), `(50%보상)`(MRI/CT) — 담보별 상이, 대표값 미정 (Decision 1: 대표값은 후속 단계에서 note로 처리) |
| 7 | 카카오페이손해보험 | 무배당 펫보험 | 35,786 | 0~3 | PET | https://kakaopayinscorp.co.kr/products/PET?dc=DMA100&utm_source=partner_damoa&utm_medium=b2c_damoa&utm_campaign=damoa_pet&utm_content=pet | | | | | | | UNVERIFIED | 미착수 — DOG/CAT 동일 상품명("무배당 펫보험")이므로 매칭 시 종 구분 주의 (계획서 R3) |
| 8 | 현대해상 | (무)현대해상다이렉트굿앤굿우리펫보험(Hi2601) 1종<반려견>_스탠다드플랜 | 41,760 | 19~80 | OWNER | https://direct.hi.co.kr/service.do?m=108256981a&petType=D&... | | | | | | | UNVERIFIED | 미착수. 자매 상품(#21, CAT 스탠다드플랜)에서 "50/70/80/90% 선택형" 확인됨 — 동일 구조일 가능성 높으나 DOG 상품 자체는 미검증 |
| 9 | KB손보 | KB 다이렉트 금쪽같은 펫보험(강아지)(무배당)(26.07) | 42,903 | 0~80 | OWNER | https://direct.kbinsure.co.kr/home/#/GL/LPD/LT_CM0101M/ | | | | | | | UNVERIFIED | 미착수 |
| 10 | 현대해상 | (무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 1종<반려견>_표준플랜 | 44,410 | 1~19 | OWNER | https://mdirect.hi.co.kr/service.do?m=5e5408d724&... | | | | | | | UNVERIFIED | 미착수 |
| 11 | KB손보 | KB 다이렉트 금쪽같은 펫보험(강아지)(재가입용)(무배당)(26.05) | 48,914 | 1~19 | PET | `'ㅡ'` (무효) | | | | | | | UNVERIFIED | **product_url 무효 — 별도 검색 필요 (파일럿 대상 아님, 지시서 명시)** |
| 12 | 롯데손보 | 댕댕이랑 여행갈땐보험 | 502.50 (연납) | 0~20 | PET | https://alice.lotteins.co.kr | — | — | — | — | — | — | **N/A** | `product_category=LIABILITY_TRAVEL` 예정(계획서 S4) — 의료비 담보 0건(배상책임/위탁비용/양육자금뿐)이라 환급률 리서치 대상 아님 |
| 13 | 마이브라운 | 강아지만 생각하는 강아지보험 | 27,595 | 0~10 | PET | `www.mybrown.co.kr` (스킴 없음, 무효) | | | | | | | UNVERIFIED | **product_url 무효 — 별도 검색 필요 (파일럿 대상 아님, 지시서 명시)** |

### CAT (12건)

| # | 회사명 | 상품명 | 월납환산보험료 | 가입연령 | age_basis | 원본 product_url | 환급률 | 자기부담금 | 자기부담금 기준 | 적용순서 | 연간한도 | 리서치 출처 URL | confidence | 담보별 차이 노트 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 14 | 삼성화재 | 무배당 삼성화재 다이렉트 반려묘보험(2601.7)(자동갱신형) | 21,541 | 0~80 | OWNER | http://direct.samsungfire.com/CR_MyAnycarWeb/overture_index.jsp?OTK=PE2304OB100 | **50%** | | | | | 원본 엑셀 `Desktop/펫보험/` | **CONFIRMED_OWN_COVERAGE_NAME** | 원본 엑셀 보장명에 `(50%보상형)` ×4담보 명시 |
| 15 | 카카오페이손해보험 | 무배당 펫보험 | 28,322 | 0~3 | PET | https://kakaopayinscorp.co.kr/products/PET?dc=DMA100&... | | | | | | | UNVERIFIED | 미착수 — DOG(#7)와 동일 상품명, 종 구분 주의 |
| 16 | 현대해상 | (무)현대해상다이렉트굿앤굿우리펫보험(재가입용)(Hi2605) 2종<반려묘>_표준플랜 | 28,650 | 1~19 | OWNER | https://mdirect.hi.co.kr/service.do?m=5e5408d724&... | | | | | | | UNVERIFIED | 미착수 |
| 17 | 농협손보 | (무) NH다이렉트펫앤미든든보험[2종:고양이]2604 | 29,336 | 0~80 | OWNER | https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314522 | | | | | | | UNVERIFIED | 미착수 — 자매 상품(#3, DOG)에서 "50/70% 선택형" 정보 발견, 동일 구조일 가능성 |
| 18 | KB손보 | KB 다이렉트 금쪽같은 펫보험(고양이)(재가입용)(무배당)(26.05) | 31,815 | 1~19 | PET | `'ㅡ'` (무효) | | | | | | | UNVERIFIED | **product_url 무효 — 별도 검색 필요 (파일럿 대상 아님, 지시서 명시)** |
| 19 | 메리츠화재 | (무) 펫퍼민트 Cat&Family보험 다이렉트2604 | 32,432 | 18~80 | OWNER | https://store.meritzfire.com/pet/product-cat.do#!/ | **70%** | **30,000원** | | | | 원본 엑셀 `Desktop/펫보험/` | **CONFIRMED_OWN_COVERAGE_NAME** | 원본 엑셀 비고 `70%보장형 자기부담금 3만원` 명시. 자기부담금 기준(건당/연간)·적용순서는 원본에 없어 미확인 |
| 20 | DB손보 | (무)다이렉트 펫블리 반려묘 의료비보험2605(CM)(재가입용) | 32,522 | 1~19 | PET | https://www.directdb.co.kr/ltm/prd/cpnmcat/custInfoView.do?searchPdcCd=31067&searchPdcTrtHistCd=00&pdcDvcd=l_pet_cat | | | | | | | UNVERIFIED | 미착수 |
| 21 | 현대해상 | (무)현대해상다이렉트굿앤굿우리펫보험(Hi2601) 2종<반려묘>_스탠다드플랜 | 32,910 | 19~80 | OWNER | https://direct.hi.co.kr/service.do?m=108256981a&petType=C&... | | | | | | | UNVERIFIED | **파일럿 대상 — 아래 "파일럿 리서치 로그 #2" 참조. 보상비율 50/70/80/90%·자부담 1만/3만/5만원 "선택형" 정보 발견했으나 이 상품(스탠다드플랜)의 실제 선택 조합 특정 불가로 미확정** |
| 22 | DB손보 | (무)다이렉트 펫블리 반려묘보험2607(CM) | 34,692 | 0~80 | OWNER | https://www.directdb.co.kr/ltm/prd/cpnmcat/custInfoView.do?searchPdcCd=31067&searchPdcTrtHistCd=00&pdcDvcd=l_pet_cat | | | | | | | UNVERIFIED | 미착수 |
| 23 | KB손보 | KB 다이렉트 금쪽같은 펫보험(고양이)(무배당)(26.07) | 35,158 | 0~80 | OWNER | https://direct.kbinsure.co.kr/home/#/GL/LPC/LT_CM0101M/ | | | | | | | UNVERIFIED | 미착수 |
| 24 | 마이브라운 | 고양이만 생각하는 고양이보험 | 30,843 | 0~10 | PET | `www.mybrown.co.kr` (스킴 없음, 무효) | | | | | | | UNVERIFIED | **product_url 무효 — 별도 검색 필요 (파일럿 대상 아님, 지시서 명시)** |

---

## 2. 원본 확정 5건 (리서치 불필요 — 지시서 2단계)

| 상품 | 값 | 근거 | confidence |
|---|---|---|---|
| 삼성화재 강아지 (#1) | 50% | 원본 엑셀 보장명 `(50%보상형)` | CONFIRMED_OWN_COVERAGE_NAME |
| 삼성화재 고양이 (#14) | 50% | 원본 엑셀 보장명 `(50%보상형)` ×4담보 | CONFIRMED_OWN_COVERAGE_NAME |
| DB손보 강아지 재가입용 (#6) | 항암 70% / MRI·CT 50% | 원본 엑셀 보장명 `(70%보상Ⅱ)`, `(50%보상)` | CONFIRMED_OWN_COVERAGE_NAME |
| 메리츠 강아지 (#5) | 70%, 자부담 3만원 | 원본 엑셀 비고 `70%보장형 자기부담금 3만원` | CONFIRMED_OWN_COVERAGE_NAME |
| 메리츠 고양이 (#19) | 70%, 자부담 3만원 | 원본 엑셀 비고 `70%보장형 자기부담금 3만원` | CONFIRMED_OWN_COVERAGE_NAME |

**주의 (모두 공통):** 자기부담금 기준(`PER_VISIT`/`PER_YEAR`)과 적용순서(`BEFORE_RATE`/`AFTER_RATE`)는 원본 엑셀 비고에 명시되지 않아 이 5건에서도 미확인이다. 메리츠 2건은 자기부담금 금액(3만원)만 확정이고 기준·순서는 별도 확인이 필요하다.

---

## 3. 파일럿 리서치 로그 (지시서 3단계 — 2건)

### 파일럿 #1 — 농협손보 강아지 [1종:강아지] (product #3)

- **URL:** https://www.nhfire.co.kr/product/retrieveProduct.nhfire?pdtCd=D314512
- **시작:** 2026-08-12 15:36:09 / **종료:** 2026-08-12 15:37:49 (**약 1분 40초**)
- **시도 횟수:** 3회
  1. `WebFetch` 공식 상품 페이지(nhfire.co.kr) → **타임아웃(60초)**, 봇 차단 또는 렌더링 지연 추정
  2. `WebSearch` "NH다이렉트펫앤미든든보험 강아지 보상비율 자기부담금 통원의료비" → 비공식 2차 출처(대리점 소개 페이지)에서 "**일반형 강아지는 보상비율 50%, 70%를 선택**, 자기부담금은 **3만원, 5만원 중 선택**"이라는 정보 확인. 그러나 이 정보는 상품코드 `D314512`(즉 V3의 33,801원 상품)가 실제 어느 조합을 선택했는지 특정하지 않음
  3. `WebFetch` 대체 공식 사이트(`npet-insure.co.kr`) → **DNS 조회 실패**(`ENOTFOUND`)
- **결론:** **UNVERIFIED.** 공식 약관/상품페이지 접근 실패. 2차 출처는 "선택형 상품"이라는 구조만 확인해줄 뿐 구체적 확정값을 주지 않음 — 추측 금지 원칙에 따라 미기재.
- **발견한 함의:** 농협손보 펫보험은 환급률·자기부담금이 **가입 시 선택하는 옵션**이다. V3 시드가 특정 프리미엄(33,801원)으로 고정 저장했다는 것은 원본 엑셀 조사 시점에 특정 조합이 선택되었다는 뜻인데, 그 조합을 웹에서 역으로 특정할 신뢰 가능한 방법이 없다. PDF 상품설명서(회당 다운로드 필요) 없이는 확정 불가할 가능성이 높다.

### 파일럿 #2 — 현대해상 고양이 스탠다드플랜 (product #21)

- **URL:** https://direct.hi.co.kr/service.do?m=108256981a&petType=C&utm_source=damoa&...
- **시작:** 2026-08-12 15:38:05 / **종료:** 2026-08-12 15:38:37 (**약 32초**)
- **시도 횟수:** 3회
  1. `WebFetch` 원본 URL → **연결 재설정 오류**(`ECONNRESET`, 파라미터가 붙은 캠페인 링크라 차단 추정)
  2. `WebSearch` "현대해상 굿앤굿우리펫보험 반려묘 스탠다드플랜 보상비율 자기부담금" → 2차 출처에서 "의료비는 보상비율 **50/70/80/90% 중 선택**, 자기부담금 **1만/3만/5만원 중 선택**"이라는 정보 확인. "스탠다드플랜"이라는 구체적 플랜명에 대한 값은 검색 결과에 없음
  3. `WebFetch` 현대해상 공식 펫보험 소개 페이지(파라미터 없는 URL) → **접속 성공**, 그러나 페이지 콘텐츠에 보상비율·자기부담금 수치가 없음(마케팅 소개 페이지)
- **결론:** **UNVERIFIED.** 3번째 시도로 공식 도메인 접속에는 성공했지만 필요한 수치가 없는 페이지였음. 파일럿 #1과 동일하게 "선택형 구조"만 확인되고 이 상품의 실제 선택값은 미확정.
- **발견한 함의:** 현대해상도 농협손보와 같은 "선택형" 구조. 24개 상품 중 대기업(현대해상·농협손보 등) 상품 다수가 이 구조일 가능성이 있어, 계획서가 가정한 "약관에서 고정값 하나를 찾는다"는 리서치 모델이 이 상품군에는 맞지 않을 수 있음.

---

## 4. 다음 단계 (리드 지시 대기)

파일럿 결과에 따른 판단은 최종 응답(에이전트 보고)에 기재. 이 섹션은 리드 지시 확정 후 갱신한다.
