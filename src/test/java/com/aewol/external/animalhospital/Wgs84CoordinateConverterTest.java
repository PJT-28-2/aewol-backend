package com.aewol.external.animalhospital;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 데이터포털 응답으로 정밀도를 검증하지 못했으므로, 여기서는 변환 결과가
 * 대한민국 대략적 위경도 범위 안에 들어오는지(축 순서/CRS 정의 오류 등 치명적 결함)만 확인한다.
 */
class Wgs84CoordinateConverterTest {

    @Test
    @DisplayName("중부원점TM 좌표를 변환하면 대한민국 위경도 범위 안에 들어온다")
    void should_convertWithinKoreaBounds_when_givenCentralBeltTmCoordinate() {
        Wgs84CoordinateConverter converter = new Wgs84CoordinateConverter();

        // 원점(x_0=200000, y_0=500000)에 가까운 좌표 — 원점 자체가 (lon=127, lat=38)에 대응해야 한다.
        double[] wgs84 = converter.toWgs84(200000.0, 500000.0);

        double longitude = wgs84[0];
        double latitude = wgs84[1];
        assertTrue(latitude > 33.0 && latitude < 43.0, "latitude out of Korea bounds: " + latitude);
        assertTrue(longitude > 124.0 && longitude < 132.0, "longitude out of Korea bounds: " + longitude);
    }
}
