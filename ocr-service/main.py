from io import BytesIO
from typing import List, Optional

import numpy as np
from fastapi import FastAPI, File, HTTPException, UploadFile
from paddleocr import PaddleOCR
from PIL import Image, ImageOps
from pydantic import BaseModel

from parser import OcrLine as ParserOcrLine
from parser import parse_receipt

app = FastAPI(title="aewol-ocr-service")

_ocr_engine: PaddleOCR | None = None


def get_ocr_engine() -> PaddleOCR:
    global _ocr_engine
    if _ocr_engine is None:
        _ocr_engine = PaddleOCR(
            use_angle_cls=True,
            lang="korean",
            # 감열지 영수증은 좁은 라벨 컬럼에 글자가 촘촘히 붙어 있어 기본값(960)으로
            # 축소하면 세부가 뭉개진다. 원본 해상도를 더 살리고, 박스 확장 비율도
            # 키워 인접 글자가 한 글자씩 쪼개져 인식되는 걸 줄인다.
            det_limit_side_len=2500,
            det_db_unclip_ratio=2.2,
        )
    return _ocr_engine


class OcrLine(BaseModel):
    text: str
    confidence: float
    box: List[List[float]]


class OcrResponse(BaseModel):
    lines: List[OcrLine]


class ReceiptItem(BaseModel):
    name: str
    quantity: float
    amount: float


class ReceiptExtractionResponse(BaseModel):
    hospital_name: Optional[str] = None
    treatment_date: Optional[str] = None
    items: List[ReceiptItem] = []
    total_amount: Optional[float] = None
    vet_name: Optional[str] = None


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


def _decode_image(image_bytes: bytes) -> np.ndarray:
    # 휴대폰으로 찍은 영수증 사진은 EXIF Orientation 태그로 회전 정보만 들고
    # 픽셀 자체는 눕혀서 저장되는 경우가 많다. cv2/PaddleOCR은 이 태그를 무시하고
    # 픽셀을 그대로 읽어서, 보정 없이 넘기면 텍스트가 90도 돌아간 채로 인식돼
    # OCR 결과가 통째로 깨진다. PIL로 미리 정방향으로 맞춰서 넘긴다.
    pil_image = Image.open(BytesIO(image_bytes))
    pil_image = ImageOps.exif_transpose(pil_image)
    pil_image = pil_image.convert("RGB")
    return np.array(pil_image)[:, :, ::-1]  # RGB -> BGR (cv2/PaddleOCR 관례)


def _run_ocr(image_bytes: bytes) -> List[ParserOcrLine]:
    engine = get_ocr_engine()
    image_array = _decode_image(image_bytes)
    result = engine.ocr(image_array, cls=True)

    lines: List[ParserOcrLine] = []
    for page in result or []:
        for box, (text, confidence) in page or []:
            lines.append({"text": text, "confidence": confidence, "box": box})
    return lines


async def _read_image(image: UploadFile) -> bytes:
    if not image.content_type or not image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="image content-type required")

    image_bytes = await image.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="empty file")
    return image_bytes


@app.post("/ocr", response_model=OcrResponse)
async def run_ocr(image: UploadFile = File(...)) -> OcrResponse:
    image_bytes = await _read_image(image)
    lines = _run_ocr(image_bytes)
    return OcrResponse(lines=[OcrLine(**line) for line in lines])


@app.post("/extract-receipt", response_model=ReceiptExtractionResponse)
async def extract_receipt(image: UploadFile = File(...)) -> ReceiptExtractionResponse:
    image_bytes = await _read_image(image)
    lines = _run_ocr(image_bytes)
    return ReceiptExtractionResponse(**parse_receipt(lines))
