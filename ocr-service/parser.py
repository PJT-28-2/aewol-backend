import re
from typing import List, Optional, TypedDict


class OcrLine(TypedDict):
    text: str
    confidence: float
    box: List[List[float]]


class ReceiptItem(TypedDict):
    name: str
    quantity: float
    amount: float


DATE_PATTERNS = [
    re.compile(r"(\d{4})[.\-/](\d{1,2})[.\-/](\d{1,2})"),
    re.compile(r"(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일"),
]

# 우선순위 순서: "합계"는 "비과세품목합계" 같은 다른 소계 줄에도 걸리는 일반 키워드라
# 가장 낮은 우선순위로 둔다. 실제 영수증에서 "청구금액"이 가장 신뢰할 수 있는 최종 금액이었다.
TOTAL_KEYWORD_TIERS = [
    ["청구금액", "청구 금액"],
    ["결제금액", "결제 금액", "총금액", "총 금액", "총액", "총진료비", "총 진료비"],
    ["합계"],
]
TOTAL_KEYWORDS = [keyword for tier in TOTAL_KEYWORD_TIERS for keyword in tier]
VET_KEYWORDS = ["수의사", "원장", "담당의"]
HOSPITAL_KEYWORDS = ["동물병원", "동물의료센터", "동물메디컬센터"]

AMOUNT_RE = re.compile(r"[\d,]+")
ITEM_LINE_RE = re.compile(r"^(?P<name>.+?)\s+(?P<qty>\d+)\s+(?P<amount>[\d,]+)\s*원?$")
TIME_RE = re.compile(r"\d{1,2}:\d{2}:\d{2}")


def _to_number(raw: str) -> Optional[float]:
    digits = AMOUNT_RE.search(raw)
    if not digits:
        return None
    try:
        return float(digits.group().replace(",", ""))
    except ValueError:
        return None


def extract_treatment_date(lines: List[OcrLine]) -> Optional[str]:
    for line in lines:
        for pattern in DATE_PATTERNS:
            match = pattern.search(line["text"])
            if match:
                year, month, day = match.groups()
                return f"{int(year):04d}-{int(month):02d}-{int(day):02d}"
    return None


def _looks_like_time_or_date(text: str) -> bool:
    if TIME_RE.search(text):
        return True
    return any(pattern.search(text) for pattern in DATE_PATTERNS)


def _numeric_value_at(lines: List[OcrLine], index: int) -> Optional[str]:
    if index < 0 or index >= len(lines):
        return None
    text = lines[index]["text"]
    if _looks_like_time_or_date(text):
        return None
    digits = re.sub(r"[^0-9]", "", text)
    if not digits:
        return None
    # 큰 금액이 "35"+"000"처럼 인접한 줄로 쪼개져 인식되는 경우를 이어붙인다.
    next_index = index + 1
    if next_index < len(lines) and not _looks_like_time_or_date(lines[next_index]["text"]):
        next_digits = re.sub(r"[^0-9]", "", lines[next_index]["text"])
        if next_digits and len(digits) <= 3 and len(next_digits) == 3:
            return digits + next_digits
    return digits


def extract_total_amount(lines: List[OcrLine]) -> Optional[float]:
    for keywords in TOTAL_KEYWORD_TIERS:
        for index, line in enumerate(lines):
            if not any(keyword in line["text"] for keyword in keywords):
                continue
            # 라벨과 금액의 상대 위치가 영수증마다 달라(라벨 다음 줄일 수도, 이전 줄일
            # 수도 있음) 같은 줄 -> 다음 줄 -> 이전 줄 순으로 찾는다. 시간/날짜 줄은
            # _numeric_value_at에서 걸러지므로 다음 줄이 타임스탬프인 경우 자동으로
            # 이전 줄로 넘어간다.
            for candidate_index in (index, index + 1, index - 1):
                digits = _numeric_value_at(lines, candidate_index)
                if digits:
                    try:
                        return float(digits)
                    except ValueError:
                        continue
    return None


def extract_hospital_name(lines: List[OcrLine]) -> Optional[str]:
    # 이전에는 키워드 매칭에 실패하면 최상단 줄을 병원명으로 추측했는데, 실제
    # 영수증에서 그 줄이 "Serial No" 같은 전혀 다른 텍스트인 경우가 있었다(모델
    # 신뢰도 자체는 높아 신뢰도 필터링으로도 못 거름). 틀린 값을 그럴듯하게 채워
    # 사용자가 오탈지 못하게 하느니, 못 찾으면 null로 남겨 확인을 유도한다.
    for line in lines:
        if any(keyword in line["text"] for keyword in HOSPITAL_KEYWORDS):
            return line["text"].strip()
    return None


def extract_vet_name(lines: List[OcrLine]) -> Optional[str]:
    for line in lines:
        for keyword in VET_KEYWORDS:
            if keyword in line["text"]:
                remainder = line["text"].replace(keyword, "").strip(" :·-")
                return remainder or None
    return None


def extract_items(lines: List[OcrLine]) -> List[ReceiptItem]:
    items: List[ReceiptItem] = []
    for line in lines:
        match = ITEM_LINE_RE.match(line["text"].strip())
        if not match:
            continue
        name = match.group("name").strip()
        if any(keyword in name for keyword in TOTAL_KEYWORDS + HOSPITAL_KEYWORDS):
            continue
        quantity = _to_number(match.group("qty"))
        amount = _to_number(match.group("amount"))
        if quantity is None or amount is None:
            continue
        items.append({"name": name, "quantity": quantity, "amount": amount})
    return items


def parse_receipt(lines: List[OcrLine]) -> dict:
    return {
        "hospital_name": extract_hospital_name(lines),
        "treatment_date": extract_treatment_date(lines),
        "items": extract_items(lines),
        "total_amount": extract_total_amount(lines),
        "vet_name": extract_vet_name(lines),
    }
