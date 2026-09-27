package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.MockRestServiceServer.bindTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.audigo.domain.travel.config.KakaoTransitProperties;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class KakaoPublicTransitClientTest {

    private static final LocalDateTime DEPARTURE_AT = LocalDateTime.of(2026, 9, 26, 14, 0, 0);

    @Test
    void 현재시각과_좌표를_카카오에_전달하고_예상도착시간을_변환한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bindTo(builder).build();
        server.expect(request -> {
                    assertThat(request.getURI().getPath())
                            .isEqualTo("/affiliate/publictransit/v1/multimodal/directions");
                    var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
                    assertThat(query.getFirst("start")).isEqualTo("127.100000,37.500000");
                    assertThat(query.getFirst("goal")).isEqualTo("127.200000,37.600000");
                    assertThat(query.getFirst("date")).isEqualTo("20260926");
                    assertThat(query.getFirst("hhmmss")).isEqualTo("140000");
                    assertThat(query.getFirst("day_type")).isEqualTo("Saturday");
                    assertThat(query.getFirst("route_type")).isEqualTo("All");
                })
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK test-key"))
                .andRespond(withSuccess(
                        """
                        {
                          "trans_id": "transit-1",
                          "result_code": 0,
                          "result_message": "성공",
                          "journeys": [
                            {
                              "summary": {
                                "total_time": 2280,
                                "distance": 6400,
                                "waiting_time": 120,
                                "transfer_count": 1
                              },
                              "sections": []
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        KakaoPublicTransitClient client = new KakaoPublicTransitClient(
                builder,
                new KakaoTransitProperties(
                        "https://apis-navi.kakaomobility.com/affiliate/publictransit/v1/multimodal/directions",
                        "test-key"
                )
        );

        KakaoPublicTransitClient.RouteResult result = client.findRoute(
                new BigDecimal("127.100000"),
                new BigDecimal("37.500000"),
                new BigDecimal("127.200000"),
                new BigDecimal("37.600000"),
                DEPARTURE_AT,
                KakaoPublicTransitClient.RouteType.ALL
        );

        assertThat(result.transactionId()).isEqualTo("transit-1");
        assertThat(result.requestedDepartureAt()).isEqualTo(DEPARTURE_AT);
        assertThat(result.estimatedArrivalAt()).isEqualTo(DEPARTURE_AT.plusSeconds(2280));
        assertThat(result.totalTimeSeconds()).isEqualTo(2280);
        assertThat(result.waitingTimeSeconds()).isEqualTo(120);
        assertThat(result.distanceMeter()).isEqualTo(6400);
        assertThat(result.transferCount()).isEqualTo(1);
        server.verify();
    }

    @Test
    void 카카오_응답에_경로가_없으면_예외를_던진다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bindTo(builder).build();
        server.expect(request -> {
                    var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
                    assertThat(query.getFirst("route_type")).isEqualTo("Bus");
                })
                .andRespond(withSuccess(
                        """
                        {
                          "trans_id": "transit-2",
                          "result_code": 1001,
                          "result_message": "운행 가능한 노선이 없습니다.",
                          "journeys": []
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        KakaoPublicTransitClient client = new KakaoPublicTransitClient(
                builder,
                new KakaoTransitProperties("http://transit.test/directions", "test-key")
        );

        assertThatThrownBy(() -> client.findRoute(
                        new BigDecimal("127.1"),
                        new BigDecimal("37.5"),
                        new BigDecimal("127.2"),
                        new BigDecimal("37.6"),
                        DEPARTURE_AT,
                        KakaoPublicTransitClient.RouteType.BUS
                ))
                .isInstanceOf(KakaoPublicTransitClient.KakaoPublicTransitException.class)
                .hasMessageContaining("경로를 찾지 못했습니다");
        server.verify();
    }

    @Test
    void 카카오_REST_API_키가_없으면_호출하지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bindTo(builder).build();
        KakaoPublicTransitClient client = new KakaoPublicTransitClient(
                builder,
                new KakaoTransitProperties("http://transit.test/directions", "")
        );

        assertThatThrownBy(() -> client.findRoute(
                        new BigDecimal("127.1"),
                        new BigDecimal("37.5"),
                        new BigDecimal("127.2"),
                        new BigDecimal("37.6"),
                        DEPARTURE_AT,
                        KakaoPublicTransitClient.RouteType.ALL
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("카카오 REST API 키");
        server.verify();
    }
}
