from io import BytesIO
from typing import List, Optional

import cv2
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
        # paddleocr 3.7로 업그레이드. 한국어 인식 모델은 korean_PP-OCRv5_mobile_rec
        # 하나뿐이라(PP-OCRv6 통합 다국어 모델은 한국어 미지원) 실질적으로 고를 수 있는
        # 건 검출기뿐이다. server 검출기(medium, 기본값)와 small 검출기를 실측 비교한
        # 결과 병원명 인식은 둘 다 실행마다 결과가 갈리는 비결정적 필드였고(server가
        # 근소하게 나을 때도 있었지만 재현되지 않음), 날짜/총액은 둘 다 100% 안정적이었다.
        # 정확도 차이가 신뢰할 수 없는 수준이라 속도가 2~4배 빠른 small을 택한다.
        # 문서방향분류/왜곡보정은 이미 _decode_image()에서 EXIF 보정을 직접 하고 있어
        # 꺼서 속도를 아낀다. textline 방향분류는 기존 use_angle_cls=True와 동등하게
        # 유지한다.
        _ocr_engine = PaddleOCR(
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=True,
            text_detection_model_name="PP-OCRv6_small_det",
            text_recognition_model_name="korean_PP-OCRv5_mobile_rec",
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


def _enhance_contrast(bgr_image: np.ndarray) -> np.ndarray:
    # 감열지 영수증 사진은 대비가 낮고 흐릿한 경우가 많아 좁은 라벨 컬럼 글자가
    # 뭉개지기 쉽다. 밝기(L) 채널에만 CLAHE(지역 대비 강화)를 적용해 색 정보는
    # 유지한 채 글자 획을 또렷하게 만든다.
    lab = cv2.cvtColor(bgr_image, cv2.COLOR_BGR2LAB)
    l_channel, a_channel, b_channel = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    l_channel = clahe.apply(l_channel)
    lab = cv2.merge((l_channel, a_channel, b_channel))
    return cv2.cvtColor(lab, cv2.COLOR_LAB2BGR)


def _decode_image(image_bytes: bytes) -> np.ndarray:
    # 휴대폰으로 찍은 영수증 사진은 EXIF Orientation 태그로 회전 정보만 들고
    # 픽셀 자체는 눕혀서 저장되는 경우가 많다. cv2/PaddleOCR은 이 태그를 무시하고
    # 픽셀을 그대로 읽어서, 보정 없이 넘기면 텍스트가 90도 돌아간 채로 인식돼
    # OCR 결과가 통째로 깨진다. PIL로 미리 정방향으로 맞춰서 넘긴다.
    pil_image = Image.open(BytesIO(image_bytes))
    pil_image = ImageOps.exif_transpose(pil_image)
    pil_image = pil_image.convert("RGB")
    bgr_image = np.array(pil_image)[:, :, ::-1]  # RGB -> BGR (cv2/PaddleOCR 관례)
    return _enhance_contrast(bgr_image)


def _run_ocr(image_bytes: bytes) -> List[ParserOcrLine]:
    engine = get_ocr_engine()
    image_array = _decode_image(image_bytes)
    result = list(engine.predict(image_array))

    lines: List[ParserOcrLine] = []
    for page in result:
        # paddleocr 3.x는 결과를 .json(dict) 속성으로 노출한다. 최상위 또는
        # "res" 서브키 아래에 rec_texts/rec_scores/dt_polys가 들어있다(버전에 따라
        # 다를 수 있어 둘 다 지원).
        data = page.json
        payload = data.get("res", data)
        texts = payload.get("rec_texts", [])
        scores = payload.get("rec_scores", [])
        polys = payload.get("dt_polys", [])
        for text, confidence, poly in zip(texts, scores, polys):
            box = poly.tolist() if hasattr(poly, "tolist") else poly
            lines.append({"text": text, "confidence": float(confidence), "box": box})
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
