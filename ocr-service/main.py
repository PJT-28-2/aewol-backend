from contextlib import asynccontextmanager
from io import BytesIO
from typing import List, Optional

import cv2
import numpy as np
from fastapi import FastAPI, File, HTTPException, UploadFile
from PIL import Image, ImageOps
from pydantic import BaseModel
from rapidocr import EngineType, LangRec, ModelType, OCRVersion, RapidOCR

from parser import OcrLine as ParserOcrLine
from parser import parse_receipt

_ocr_engine: RapidOCR | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 모델 로딩을 첫 요청 때 지연시키는 대신 컨테이너 기동 시점에 미리 끝내둔다.
    # 컨테이너가 healthy로 뜨기까지는 그만큼 오래 걸리지만, 그 이후 첫 실제
    # 요청부터 바로 predict() 시간만 든다.
    get_ocr_engine()
    yield


app = FastAPI(title="aewol-ocr-service", lifespan=lifespan)


def get_ocr_engine() -> RapidOCR:
    global _ocr_engine
    if _ocr_engine is None:
        # paddleocr(paddlepaddle 기반) 대신 RapidOCR(onnxruntime 기반)로 전환.
        # RapidOCR은 PaddleOCR 모델 가중치를 그대로 ONNX로 변환해 배포하는
        # 프로젝트라 인식 결과 자체는 기존 paddleocr "small" 설정과 동등하지만
        # (실측 확인됨 — 병원명 인식 여부까지 동일한 패턴), 무거운 paddlepaddle
        # 프레임워크 오버헤드가 없어서 연결(모델 로딩) 4~5배, 분석 2~3배 더 빠르다.
        # 검출 모델은 RapidOCR 기본값이 이미 PP-OCRv6_small_det라 별도 지정이
        # 필요 없고, 인식 모델만 한국어로 명시적으로 고정한다(한국어 지원 모델은
        # korean_PP-OCRv5_mobile_rec 하나뿐).
        #
        # use_cls(각도 분류기)는 끈다. cls는 검출된 텍스트 줄이 180도 뒤집혔는지
        # 판별해 되돌리는 보조 모델인데, 한글 라벨 조각을 자주 오분류해서 멀쩡한
        # 텍스트를 거꾸로 뒤집은 채 인식기로 넘긴다. 실측 결과 영수증 3장 모두
        # cls를 켜면 병원명이 깨져서 null이 되고, 끄면 정상 인식된다(금액/날짜는
        # 회귀 없음, 속도도 미세하게 빠름). 사진 자체의 회전은 EXIF Orientation을
        # _decode_image()에서 따로 처리하므로 cls 없이도 문제되지 않는다.
        _ocr_engine = RapidOCR(
            params={
                "Global.use_cls": False,
                "Rec.ocr_version": OCRVersion.PPOCRV5,
                "Rec.engine_type": EngineType.ONNXRUNTIME,
                "Rec.lang_type": LangRec.KOREAN,
                "Rec.model_type": ModelType.MOBILE,
            }
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
    # 픽셀 자체는 눕혀서 저장되는 경우가 많다. cv2/RapidOCR은 이 태그를 무시하고
    # 픽셀을 그대로 읽어서, 보정 없이 넘기면 텍스트가 90도 돌아간 채로 인식돼
    # OCR 결과가 통째로 깨진다. PIL로 미리 정방향으로 맞춰서 넘긴다.
    pil_image = Image.open(BytesIO(image_bytes))
    pil_image = ImageOps.exif_transpose(pil_image)
    pil_image = pil_image.convert("RGB")
    bgr_image = np.array(pil_image)[:, :, ::-1]  # RGB -> BGR (cv2/RapidOCR 관례)
    return _enhance_contrast(bgr_image)


def _run_ocr(image_bytes: bytes) -> List[ParserOcrLine]:
    engine = get_ocr_engine()
    image_array = _decode_image(image_bytes)
    result = engine(image_array)

    lines: List[ParserOcrLine] = []
    texts = result.txts or ()
    scores = result.scores or ()
    boxes = result.boxes if result.boxes is not None else ()
    for text, confidence, box in zip(texts, scores, boxes):
        box_list = box.tolist() if hasattr(box, "tolist") else box
        lines.append({"text": text, "confidence": float(confidence), "box": box_list})
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
