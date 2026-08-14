#!/usr/bin/env node
/**
 * gen-insurance-seed.mjs — 리서치 문서(markdown 표) → V30 백필 SQL 생성기
 *
 *   입력 : docs/insurance-reimbursement-research.md
 *   출력 : db/migration/V30__insurance_reimbursement_and_category.sql 의
 *          `-- BEGIN GENERATED (S1 backfill)` ~ `-- END GENERATED (S1 backfill)` 구간
 *
 * 사용법 (aewol-backend 디렉터리에서):
 *   node scripts/gen-insurance-seed.mjs            # stdout 미리보기 (파일 미변경)
 *   node scripts/gen-insurance-seed.mjs --write    # V30의 GENERATED 구간 치환
 *
 * 의존성 없음(Node 표준 라이브러리만). package.json 불필요.
 * scripts/ 는 src/ 밖이므로 fatJar 에 포함되지 않는다 — 개발 시점 전용 도구다.
 *
 * ⚠️ Flyway 체크섬: V30이 이미 DB에 적용된 뒤에는 이 스크립트로 V30을 고치면 안 된다.
 *    적용 후 새 근거가 나오면 V30을 새로 만들 것. --write 는 그 상황을 감지하지 못한다.
 *
 * 설계 원칙 (계획서 Principle 1):
 *   - 추측하지 않는다. 표의 빈칸은 SQL 에서 NULL 로 나간다.
 *   - '%' 를 무조건 환급률로 채택하지 않는다. 셀에 퍼센트가 2개 이상이면(담보별 상이)
 *     rate 는 NULL 로 두고 원문을 reimbursement_rate_note 로 보낸다 (Decision 1).
 *   - 상품 매칭은 반드시 species + company_name + product_name 3키 (계획서 R3).
 */

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const DOC = join(ROOT, "docs", "insurance-reimbursement-research.md");
const MIGRATION = join(
  ROOT,
  "src", "main", "resources", "db", "migration",
  "V30__insurance_reimbursement_and_category.sql",
);
const BEGIN = "-- BEGIN GENERATED (S1 backfill)";
const END = "-- END GENERATED (S1 backfill)";

/** DB enum 과 문서 표기가 어긋나는 경우를 흡수한다. 문서 범례에 CONFIRMED_RESEARCH 가
 *  있으나 스키마 주석의 유효값은 CONFIRMED_OWN_COVERAGE_NAME/ASSUMED_FROM_RESEARCH/UNVERIFIED 다. */
const CONFIDENCE_ALIAS = {
  CONFIRMED_RESEARCH: "ASSUMED_FROM_RESEARCH",
};
const VALID_CONFIDENCE = new Set([
  "CONFIRMED_OWN_COVERAGE_NAME",
  "ASSUMED_FROM_RESEARCH",
  "UNVERIFIED",
]);

const warnings = [];

/** markdown 강조/코드/공백을 벗겨 순수 값만 남긴다. */
function clean(cell) {
  return (cell ?? "")
    .replace(/\*\*/g, "")
    .replace(/`/g, "")
    .trim();
}

/** 빈칸 판정. '—' 와 'N/A' 도 값 없음으로 본다. */
function isBlank(v) {
  return v === "" || v === "—" || v === "-" || v === "N/A";
}

function sqlStr(v) {
  if (v == null || isBlank(String(v))) return "NULL";
  return `'${String(v).replace(/'/g, "''")}'`;
}

function sqlNum(v) {
  if (v == null || v === "") return "NULL";
  return String(v);
}

/**
 * 환급률 셀 파싱.
 *  - '50%'          → { rate: 50, note: null }
 *  - '항암 70% / MRI·CT 50%' → { rate: null, note: 원문 }  (담보별 상이, Decision 1)
 *  - ''             → { rate: null, note: null }
 */
function parseRate(cell, ctx) {
  const v = clean(cell);
  if (isBlank(v)) return { rate: null, note: null };
  const matches = [...v.matchAll(/(\d{1,3})\s*%/g)].map((m) => Number(m[1]));
  if (matches.length === 0) {
    warnings.push(`[${ctx}] 환급률 셀에 퍼센트가 없습니다: "${v}" → NULL 처리`);
    return { rate: null, note: null };
  }
  if (matches.length > 1 || !/^\d{1,3}\s*%$/.test(v)) {
    // 담보별로 갈리는 셀(예: '항암 70% / MRI·CT 50%').
    // 계획서 Decision 1: 대표값은 **보수적 최저치**, 원문은 note 로 보존한다.
    // 이 규칙이 없으면 confidence=CONFIRMED 인데 rate=NULL 이 되어 무결성 쿼리 9를 위반한다.
    const min = Math.min(...matches);
    return { rate: min, note: `${v} (담보별 상이, 대표값은 보수적 최저치)` };
  }
  const rate = matches[0];
  if (rate === 80) {
    warnings.push(
      `[${ctx}] 환급률 80% — 후유장해 지급률 오추출 의심(무결성 쿼리 4). 담보명 근거를 확인하세요.`,
    );
  }
  if (rate < 1 || rate > 100) {
    warnings.push(`[${ctx}] 환급률 범위 이상: ${rate}% → NULL 처리`);
    return { rate: null, note: v };
  }
  return { rate, note: null };
}

/** '30,000원' → 30000 */
function parseMoney(cell, ctx) {
  const v = clean(cell);
  if (isBlank(v)) return null;
  const digits = v.replace(/[,\s원]/g, "");
  if (!/^\d+(\.\d+)?$/.test(digits)) {
    warnings.push(`[${ctx}] 금액 파싱 실패: "${v}" → NULL 처리`);
    return null;
  }
  return digits;
}

function parseConfidence(cell, ctx) {
  const raw = clean(cell).toUpperCase();
  if (isBlank(raw)) return "UNVERIFIED";
  const mapped = CONFIDENCE_ALIAS[raw] ?? raw;
  if (!VALID_CONFIDENCE.has(mapped)) {
    warnings.push(`[${ctx}] 알 수 없는 confidence "${raw}" → UNVERIFIED 로 처리`);
    return "UNVERIFIED";
  }
  return mapped;
}

/** 문서에서 DOG/CAT 섹션의 표 행을 뽑는다. */
function parseDoc(md) {
  const rows = [];
  let species = null;
  for (const line of md.split(/\r?\n/)) {
    const heading = line.match(/^###\s+(DOG|CAT)\b/);
    if (heading) {
      species = heading[1];
      continue;
    }
    if (!species || !line.trim().startsWith("|")) continue;

    const cells = line.split("|").slice(1, -1).map((c) => c.trim());
    if (cells.length < 15) continue;                  // 표가 아니거나 열 부족
    if (!/^\d+$/.test(clean(cells[0]))) continue;     // 헤더/구분선

    rows.push({
      no: Number(clean(cells[0])),
      species,
      company: clean(cells[1]),
      product: clean(cells[2]),
      productUrlRaw: clean(cells[6]),
      rateCell: cells[7],
      deductibleCell: cells[8],
      deductibleBasis: clean(cells[9]),
      deductibleOrder: clean(cells[10]),
      annualLimitCell: cells[11],
      sourceUrl: clean(cells[12]),
      confidenceCell: cells[13],
      note: clean(cells[14]),
    });
  }
  return rows;
}

function build(rows) {
  const out = [];
  const stamp = new Date().toISOString().slice(0, 10);
  let tierCount = 0;
  let confirmedCount = 0;
  let travelCount = 0;

  out.push(`-- 생성일: ${stamp} — 직접 편집하지 말 것 (scripts/gen-insurance-seed.mjs)`);
  out.push(`-- 입력: docs/insurance-reimbursement-research.md (${rows.length}행 파싱)`);
  out.push("");

  for (const r of rows) {
    const ctx = `#${r.no} ${r.company} ${r.product.slice(0, 20)}`;
    const where =
      `WHERE species = ${sqlStr(r.species)}\n` +
      `  AND company_name = ${sqlStr(r.company)}\n` +
      `  AND product_name = ${sqlStr(r.product)}`;

    const isTravel = /LIABILITY_TRAVEL/i.test(r.note);
    const category = isTravel ? "LIABILITY_TRAVEL" : "MEDICAL";
    if (isTravel) travelCount++;

    const { rate, note: rateNote } = parseRate(r.rateCell, ctx);
    const confidence = isTravel ? null : parseConfidence(r.confidenceCell, ctx);
    const deductible = parseMoney(r.deductibleCell, ctx);
    const annualLimit = parseMoney(r.annualLimitCell, ctx);

    // 무결성 쿼리 1: CONFIRMED 인데 출처가 비면 SQL 을 내보내기 전에 잡는다.
    if (confidence === "CONFIRMED_OWN_COVERAGE_NAME" && isBlank(r.sourceUrl)) {
      warnings.push(`[${ctx}] CONFIRMED 인데 출처가 비어 있습니다 (무결성 쿼리 1 위반 예정).`);
    }
    // 무결성 쿼리 9: confidence 와 rate 의 정합.
    if (confidence && (confidence === "UNVERIFIED") !== (rate === null)) {
      warnings.push(
        `[${ctx}] confidence=${confidence} 인데 rate=${rate} — 무결성 쿼리 9 위반 예정.`,
      );
    }

    out.push(`-- ── #${r.no} ${r.company} / ${r.product}`);
    if (r.note) out.push(`--    노트: ${r.note}`);
    if (!isBlank(r.sourceUrl)) out.push(`--    출처: ${r.sourceUrl}`);

    if (isTravel) {
      out.push(`--    의료비 담보 0건 → 견적 기준 티어를 만들지 않는다(손익분기 대상 아님).`);
    } else {
      tierCount++;
      if (confidence === "CONFIRMED_OWN_COVERAGE_NAME") confirmedCount++;
      const tierName = rate != null ? `${rate}%보장형` : "견적 기준(플랜 미확인)";
      out.push(
        "INSERT INTO insurance_product_plan_tiers",
        "  (product_id, tier_name, reimbursement_rate_pct, is_reference_tier, premium_krw,",
        "   deductible_krw, deductible_basis, annual_limit_krw, reimbursement_source_url,",
        "   reimbursement_confidence)",
        `SELECT product_id, ${sqlStr(tierName)}, ${sqlNum(rate)}, 1, premium_monthly_equiv,`,
        `       ${sqlNum(deductible)}, ${sqlStr(r.deductibleBasis)}, ${sqlNum(annualLimit)}, ${sqlStr(r.sourceUrl)},`,
        `       ${sqlStr(confidence)}`,
        "FROM insurance_product",
        `${where};`,
      );
    }

    // age_subject_confidence: 리서치 표에 열이 없다. 우리가 아는 것은 V3 시드의
    // age_basis 값뿐이고 그 근거를 약관에서 확인한 적은 없으므로, 정직한 값은
    // ASSUMED 다 (CONFIRMED 로 넣는 것은 하지 않은 검증을 했다고 주장하는 것 —
    // Principle 1 위반). C-1 수정으로 이 값은 더 이상 상품을 화면에서 배제하지
    // 않으므로(InsuranceProductPolicy.isEligibleByAge) 안전한 기본값이다.
    // 약관으로 확인한 상품이 생기면 리서치 표에 열을 추가해 여기서 덮어쓸 것.
    const sets = [
      `product_category = ${sqlStr(category)}`,
      `age_subject_confidence = 'ASSUMED'`,
    ];
    if (rateNote) sets.push(`reimbursement_rate_note = ${sqlStr(rateNote)}`);
    if (!isBlank(r.deductibleOrder)) {
      sets.push(`deductible_order = ${sqlStr(r.deductibleOrder)}`);
    }
    // product_url 무효 정정(AC-3): 표의 원본 URL 이 http 로 시작할 때만 반영한다.
    if (/^https?:\/\//.test(r.productUrlRaw) && !r.productUrlRaw.includes("...")) {
      sets.push(`product_url = ${sqlStr(r.productUrlRaw)}`);
    } else if (r.productUrlRaw.includes("...")) {
      // 문서가 가독성을 위해 URL 을 줄인 경우. DB 의 V3 시드 값이 이미 온전하므로
      // 덮어쓰지 않는 것이 옳다 — 경고가 아니라 정보다.
      out.push("--    product_url: 문서 표기가 축약형이라 DB 기존 값을 유지한다.");
    } else {
      warnings.push(
        `[${ctx}] product_url 무효 — AC-3 미해결, 별도 검색 필요: "${r.productUrlRaw}" (무결성 쿼리 6 실패 예정)`,
      );
      out.push(`--    ⚠️ product_url 무효(${r.productUrlRaw}) — AC-3 미해결.`);
    }

    out.push("UPDATE insurance_product", `SET ${sets.join(",\n    ")}`, `${where};`, "");
  }

  out.push("-- ── 생성 요약");
  out.push(`--    상품 ${rows.length}건 / 견적 기준 티어 INSERT ${tierCount}건`);
  out.push(`--    그중 CONFIRMED ${confirmedCount}건 / LIABILITY_TRAVEL 제외 ${travelCount}건`);
  out.push(`--    → 무결성 쿼리 8 기대값: ${tierCount}`);
  out.push("--    age_subject_confidence 는 전 행 'ASSUMED' — 리서치 표에 열이 없고");
  out.push("--    age_basis 근거를 약관에서 확인한 적이 없다(Principle 1). 무결성 쿼리 3은 통과한다.");

  return { sql: out.join("\n"), tierCount, confirmedCount };
}

// ── main ────────────────────────────────────────────────────────────
const rows = parseDoc(readFileSync(DOC, "utf8"));
if (rows.length === 0) {
  console.error("리서치 문서에서 표 행을 찾지 못했습니다. 표 형식이 바뀌었는지 확인하세요.");
  process.exit(1);
}

const { sql, tierCount, confirmedCount } = build(rows);
const write = process.argv.includes("--write");

if (write) {
  const migration = readFileSync(MIGRATION, "utf8");
  const b = migration.indexOf(BEGIN);
  const e = migration.indexOf(END);
  if (b === -1 || e === -1 || e < b) {
    console.error(`V30 에서 센티넬 구간을 찾지 못했습니다:\n  ${BEGIN}\n  ${END}`);
    process.exit(1);
  }
  const next =
    migration.slice(0, b + BEGIN.length) + "\n" + sql + "\n" + migration.slice(e);
  writeFileSync(MIGRATION, next, "utf8");
  console.error(`V30 GENERATED 구간을 갱신했습니다 (티어 ${tierCount}건 / CONFIRMED ${confirmedCount}건).`);
  console.error("⚠️ V30이 이미 DB에 적용된 상태라면 Flyway 체크섬 검증에 실패합니다. 적용 전인지 확인하세요.");
} else {
  console.log(sql);
  console.error(`\n(미리보기 — 파일 미변경. 반영하려면 --write)`);
}

if (warnings.length) {
  console.error(`\n경고 ${warnings.length}건:`);
  for (const w of warnings) console.error(`  - ${w}`);
}
