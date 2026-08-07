package com.aewol.external.animalhospital;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.stereotype.Component;

/**
 * EPSG:5174(Bessel 중부원점 TM, 보정계수 없음) 좌표를 EPSG:4326(WGS84 위경도)로 변환한다.
 *
 * <p>행정안전부_동물_동물병원 조회서비스(data.go.kr, 서비스ID 15154952)의 데이터 설명에
 * "좌표계 : 보정계수 안들어간 Bessel 중부원점TM(EPSG:5174)"라고 명시되어 있다.
 * {@code emergency_hospital} 테이블의 latitude/longitude는 WGS84 기준이므로, 원본 X/Y
 * 좌표를 그대로 저장하면 안 되고 이 변환을 거쳐야 한다.
 *
 * <p>EPSG:5174 정의(중부원점, false easting 200000, false northing 500000, 원점 위경도
 * 38°N/127°E)와 Bessel→WGS84 3-parameter datum shift(towgs84)는 proj.org의 표준 정의를
 * 그대로 사용했다.
 */
@Component
public class Wgs84CoordinateConverter {

    private static final String EPSG_5174_PROJ4 =
            "+proj=tmerc +lat_0=38 +lon_0=127 +k=1 +x_0=200000 +y_0=500000 "
                    + "+ellps=bessel +units=m +no_defs +towgs84=-146.43,507.89,681.46";
    private static final String EPSG_4326_PROJ4 = "+proj=longlat +datum=WGS84 +no_defs";

    private final CoordinateTransform transform;

    public Wgs84CoordinateConverter() {
        // createFromName("EPSG:4326")은 proj4j가 번들하는 CRS 리소스 파일 조회에 의존하는데
        // 이 환경에서는 해당 리소스를 찾지 못해 실패한다. 두 CRS 모두 파라미터로 직접
        // 정의해서 리소스 파일 조회 자체를 우회한다.
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem source = crsFactory.createFromParameters("EPSG:5174", EPSG_5174_PROJ4);
        CoordinateReferenceSystem target = crsFactory.createFromParameters("EPSG:4326", EPSG_4326_PROJ4);
        this.transform = new CoordinateTransformFactory().createTransform(source, target);
    }

    /**
     * @param x TM 동쪽(가로) 좌표
     * @param y TM 북쪽(세로) 좌표
     * @return {longitude, latitude} (WGS84)
     */
    public double[] toWgs84(double x, double y) {
        ProjCoordinate target = new ProjCoordinate();
        transform.transform(new ProjCoordinate(x, y), target);
        return new double[]{target.x, target.y};
    }
}
