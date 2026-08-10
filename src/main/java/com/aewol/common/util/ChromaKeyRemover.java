package com.aewol.common.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 생성 이미지의 단색 초록 배경을 투명하게 바꾼다.
 *
 * <p>생성 모델은 알파 채널을 만들지 못한다. 프롬프트로 투명 배경을 요구하면 투명을
 * 나타내는 격자무늬를 그림으로 그려버리기 때문에, 단색 배경을 받아 낸 뒤 여기서 빼낸다.
 *
 * <p>배경색으로 초록을 쓰는 이유는 색 오염 때문이다. 마젠타 배경으로 실험했을 때
 * 갈색 털이 붉게 물들었다. 개·고양이 털색(갈색·검정·흰색·회색)은 초록이 가장 강한
 * 채널이 되는 경우가 거의 없어서 초록이 피사체와 가장 잘 분리된다.
 *
 * <p>외부 라이브러리 없이 JDK 표준 {@link BufferedImage}만 사용한다.
 */
@Slf4j
@Component
public class ChromaKeyRemover {

    /**
     * 초록 판정 기준. 모델이 정확한 #00FF00을 주지 않고, 가장자리로 갈수록 채도가 낮은
     * 올리브빛으로 흐려진다. "초록이 가장 강한 채널이면서 가장 약한 채널과 충분히
     * 벌어져 있는가"로 보면 선명한 초록과 흐린 올리브를 모두 잡는다.
     */
    private static final int GREEN_EXCESS = 28;

    /** 모델이 캔버스 가장자리에 흰 여백을 남기는 경우가 있어 함께 제거한다. */
    private static final int WHITE_THRESHOLD = 225;

    /** 털 가장자리에 남은 초록 기운을 눌러 줄 기준. */
    private static final int SPILL_GAP = 12;

    /**
     * @return 배경이 투명해진 PNG 바이트. 변환에 실패하면 원본을 그대로 돌려준다.
     */
    public byte[] removeGreenBackground(byte[] pngBytes) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (source == null) {
                log.warn("[CHROMA_KEY_SKIPPED] 이미지를 해석하지 못해 원본을 유지합니다.");
                return pngBytes;
            }

            int width = source.getWidth();
            int height = source.getHeight();
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int cleared = 0;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgb = source.getRGB(x, y);
                    int red = (rgb >> 16) & 0xFF;
                    int green = (rgb >> 8) & 0xFF;
                    int blue = rgb & 0xFF;

                    if (isBackground(red, green, blue)) {
                        result.setRGB(x, y, 0x00000000);
                        cleared++;
                    } else {
                        result.setRGB(x, y, 0xFF000000 | despill(red, green, blue));
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(result, "png", out);
            log.info("크로마키 제거 완료 - {}x{}, 투명 처리 {}%",
                    width, height, cleared * 100 / (width * height));
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            log.error("[CHROMA_KEY_FAILED] 배경 제거 실패 - 원본을 유지합니다.", e);
            return pngBytes;
        }
    }

    /**
     * 초록이 가장 강한 채널이고 가장 약한 채널과 충분히 벌어져 있으면 배경으로 본다.
     * 개·고양이 털색(갈색·검정·흰색·회색)은 초록이 최댓값이 되는 경우가 거의 없어
     * 피사체를 잘못 지우지 않는다. 캔버스 가장자리의 흰 여백도 함께 제거한다.
     */
    private boolean isBackground(int red, int green, int blue) {
        boolean greenScreen = green >= red && green >= blue
                && green - Math.min(red, blue) > GREEN_EXCESS;
        boolean white = red > WHITE_THRESHOLD && green > WHITE_THRESHOLD && blue > WHITE_THRESHOLD;
        return greenScreen || white;
    }

    /** 피사체 가장자리에 번진 초록을 줄인다. 빼지 않으면 밝은 배경 위에서 초록 테두리가 보인다. */
    private int despill(int red, int green, int blue) {
        int neighbour = Math.max(red, blue);
        if (green > neighbour + SPILL_GAP) {
            green = neighbour + SPILL_GAP;
        }
        return (red << 16) | (green << 8) | blue;
    }
}
