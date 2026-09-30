package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class KakaoPlaceLiveLookupServiceTest {

    @Test
    void 오픈그래프_제목에서_장소명을_추출한다() {
        String html = """
                <html><head>
                  <meta property="og:title" content="경복궁 - 카카오맵">
                </head></html>
                """;

        assertThat(KakaoPlaceLiveLookupService.extractPlaceName(html)).isEqualTo("경복궁");
    }

    @Test
    void 오픈그래프_제목이_없으면_페이지_제목을_사용한다() {
        String html = "<html><head><title>광장시장 | Kakao Map</title></head></html>";

        assertThat(KakaoPlaceLiveLookupService.extractPlaceName(html)).isEqualTo("광장시장");
    }

    @Test
    void 카카오맵_일반_페이지_제목은_장소명으로_사용하지_않는다() {
        String html = "<html><head><title>카카오맵</title></head></html>";

        assertThat(KakaoPlaceLiveLookupService.extractPlaceName(html)).isNull();
    }

    @Test
    void 메타_태그에서_장소_좌표를_추출한다() {
        String html = """
                <html><head>
                  <meta property="place:location:latitude" content="37.579617">
                  <meta property="place:location:longitude" content="126.977041">
                </head></html>
                """;

        assertThat(KakaoPlaceLiveLookupService.extractCoordinates(html))
                .isEqualTo(new KakaoPlaceLiveLookupService.Coordinates(
                        new BigDecimal("37.579617"),
                        new BigDecimal("126.977041")
                ));
    }

    @Test
    void 페이지_내부_x_y_데이터에서_장소_좌표를_추출한다() {
        String html = """
                <script>
                  window.place = {"id":"7990409","x":"126.977041","y":"37.579617"};
                </script>
                """;

        assertThat(KakaoPlaceLiveLookupService.extractCoordinates(html))
                .isEqualTo(new KakaoPlaceLiveLookupService.Coordinates(
                        new BigDecimal("37.579617"),
                        new BigDecimal("126.977041")
                ));
    }

    @Test
    void 트위터_이미지의_정적_지도_URL에서_장소_좌표를_추출한다() {
        String html = """
                <meta name="twitter:image"
                      content="http://staticmap.kakao.com/staticmap/og?type=place&amp;srs=wgs84&amp;m=127.03915202952251%2C37.52675582301717">
                """;

        assertThat(KakaoPlaceLiveLookupService.extractCoordinates(html))
                .isEqualTo(new KakaoPlaceLiveLookupService.Coordinates(
                        new BigDecimal("37.52675582301717"),
                        new BigDecimal("127.03915202952251")
                ));
    }

    @Test
    void 범위를_벗어난_좌표는_사용하지_않는다() {
        String html = "<div data-x=200 data-y=100></div>";

        assertThat(KakaoPlaceLiveLookupService.extractCoordinates(html)).isNull();
    }
}
