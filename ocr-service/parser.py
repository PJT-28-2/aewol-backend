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

TOTAL_KEYWORDS = ["합계", "총액", "총 금액", "청구금액", "결제금액", "총진료비", "총 진료비"]
VET_KEYWORDS = ["수의사", "원장", "담당의"]
HOSPITAL_KEYWORDS = ["동물병원", "동물의료센터", "동물메디컬센터"]

AMOUNT_RE = re.compile(r"[\d,]+")
ITEM_LINE_RE = re.compile(r"^(?P<name>.+?)\s+(?P<qty>\d+)\s+(?P<amount>[\d,]+)\s*원?$")


def _line_y(line: OcrLine) -> float:
    return sum(point[1] for point in line["box"]) / len(line["box"])


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


def extract_total_amount(lines: List[OcrLine]) -> Optional[float]:
    for index, line in enumerate(lines):
        text = line["text"]
        if any(keyword in text for keyword in TOTAL_KEYWORDS):
            amount = _to_number(text)
            if amount is not None:
                return amount
            if index + 1 < len(lines):
                amount = _to_number(lines[index + 1]["text"])
                if amount is not None:
                    return amount
    return None


def extract_hospital_name(lines: List[OcrLine]) -> Optional[str]:
    for line in lines:
        if any(keyword in line["text"] for keyword in HOSPITAL_KEYWORDS):
            return line["text"].strip()
    if lines:
        sorted_by_y = sorted(lines, key=_line_y)
        return sorted_by_y[0]["text"].strip()
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
