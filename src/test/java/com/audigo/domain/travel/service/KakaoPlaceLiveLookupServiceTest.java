package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;

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
}
