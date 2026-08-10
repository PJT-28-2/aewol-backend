from typing import List, Optional

from fastapi import FastAPI, File, HTTPException, UploadFile
from paddleocr import PaddleOCR
from pydantic import BaseModel

from parser import OcrLine as ParserOcrLine
from parser import parse_receipt

app = FastAPI(title="aewol-ocr-service")

_ocr_engine: PaddleOCR | None = None


def get_ocr_engine() -> PaddleOCR:
    global _ocr_engine
    if _ocr_engine is None:
        _ocr_engine = PaddleOCR(use_angle_cls=True, lang="korean")
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


def _run_ocr(image_bytes: bytes) -> List[ParserOcrLine]:
    engine = get_ocr_engine()
    result = engine.ocr(image_bytes, cls=True)

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
