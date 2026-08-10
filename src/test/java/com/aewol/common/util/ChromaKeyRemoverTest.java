package com.aewol.common.util;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChromaKeyRemoverTest {

    private final ChromaKeyRemover remover = new ChromaKeyRemover();

    @Test
    @DisplayName("초록 배경은 투명해지고 피사체는 남는다")
    void should_clearGreenBackground_and_keepSubject() throws IOException {
        // 왼쪽 절반은 초록 배경, 오른쪽 절반은 갈색 털
        byte[] png = image(4, 2, (x, y) -> x < 2 ? rgb(20, 220, 30) : rgb(180, 120, 70));

        BufferedImage result = read(remover.removeGreenBackground(png));

        assertEquals(0, alpha(result, 0, 0), "초록 배경은 투명해야 한다");
        assertEquals(0, alpha(result, 1, 1));
        assertEquals(255, alpha(result, 2, 0), "갈색 털은 남아야 한다");
        assertEquals(255, alpha(result, 3, 1));
    }

    @Test
    @DisplayName("채도가 낮은 올리브빛 배경도 제거한다")
    void should_clearDesaturatedGreen() throws IOException {
        // 가장자리로 갈수록 흐려지는 초록. 이걸 못 잡으면 얼룩이 남는다.
        byte[] png = image(2, 1, (x, y) -> rgb(150, 190, 120));

        BufferedImage result = read(remover.removeGreenBackground(png));

        assertEquals(0, alpha(result, 0, 0));
        assertEquals(0, alpha(result, 1, 0));
    }

    @Test
    @DisplayName("캔버스 가장자리의 흰 여백도 제거한다")
    void should_clearWhiteMargin() throws IOException {
        byte[] png = image(2, 1, (x, y) -> rgb(250, 250, 250));

        BufferedImage result = read(remover.removeGreenBackground(png));

        assertEquals(0, alpha(result, 0, 0));
    }

    @Test
    @DisplayName("갈색·검정·회색 털은 배경으로 오인하지 않는다")
    void should_keepCommonFurColors() throws IOException {
        int[][] furColors = {
                {180, 120, 70},   // 갈색
                {30, 30, 30},     // 검정
                {200, 200, 200},  // 회색
                {240, 235, 220},  // 크림
        };
        for (int[] fur : furColors) {
            byte[] png = image(1, 1, (x, y) -> rgb(fur[0], fur[1], fur[2]));
            BufferedImage result = read(remover.removeGreenBackground(png));
            assertEquals(255, alpha(result, 0, 0),
                    "rgb(" + fur[0] + "," + fur[1] + "," + fur[2] + ")는 남아야 한다");
        }
    }

    @Test
    @DisplayName("털 가장자리에 번진 초록 기운을 눌러 준다")
    void should_despillGreenFringe() throws IOException {
        // 초록이 과하게 섞인 가장자리 픽셀. 배경으로 지울 정도는 아니지만 보정이 필요하다.
        byte[] png = image(1, 1, (x, y) -> rgb(150, 170, 150));

        BufferedImage result = read(remover.removeGreenBackground(png));
        int pixel = result.getRGB(0, 0);
        int red = (pixel >> 16) & 0xFF;
        int green = (pixel >> 8) & 0xFF;
        int blue = pixel & 0xFF;

        assertEquals(255, alpha(result, 0, 0), "피사체로 남아야 한다");
        assertTrue(green <= Math.max(red, blue) + 12,
                "초록이 이웃 채널보다 과도하게 높으면 안 된다: g=" + green);
    }

    @Test
    @DisplayName("이미지로 해석할 수 없으면 원본을 그대로 돌려준다")
    void should_returnOriginal_when_bytesAreNotImage() {
        byte[] garbage = {1, 2, 3, 4};

        assertArrayEquals(garbage, remover.removeGreenBackground(garbage));
    }

    // ── helpers ──────────────────────────────────────────────────

    private interface PixelSource {
        int colorAt(int x, int y);
    }

    private static int rgb(int red, int green, int blue) {
        return (red << 16) | (green << 8) | blue;
    }

    private static byte[] image(int width, int height, PixelSource source) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, source.colorAt(x, y));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static int alpha(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >> 24) & 0xFF;
    }
}
