package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.MockRestServiceServer.bindTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.audigo.domain.travel.config.TagoBusArrivalProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class TagoBusArrivalClientTest {

    @Test
    void TAGO_응답을_버스_도착정보로_변환한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bindTo(builder).build();
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/arrival");
                    var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
                    assertThat(query.getFirst("cityCode")).isEqualTo("25");
                    assertThat(query.getFirst("nodeId")).isEqualTo("node-1");
                    assertThat(query.getFirst("_type")).isEqualTo("json");
                    assertThat(query.getFirst("serviceKey")).isEqualTo("test-key");
                })
                .andRespond(withSuccess(
                        """
                        {
                          "response": {
                            "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE"},
                            "body": {
                              "items": {
                                "item": [
                                  {
                                    "routeid": "route-643",
                                    "routeno": "643",
                                    "arrprevstationcnt": 3,
                                    "vehicletp": "일반",
                                    "arrtime": 480
                                  },
                                  {
                                    "routeid": "route-506",
                                    "routeno": "506",
                                    "arrprevstationcnt": 1,
                                    "vehicletp": "저상",
                                    "arrtime": 60
                                  }
                                ]
                              }
                            }
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        TagoBusArrivalClient client = new TagoBusArrivalClient(
                builder,
                new TagoBusArrivalProperties("http://tago.test/arrival", "test-key")
        );

        List<TagoBusArrivalClient.Arrival> arrivals = client.findArrivals("25", "node-1");

        assertThat(arrivals).hasSize(2);
        assertThat(arrivals.get(0).routeNumber()).isEqualTo("643");
        assertThat(arrivals.get(0).arrivalSeconds()).isEqualTo(480);
        assertThat(arrivals.get(1).remainingStops()).isEqualTo(1);
        server.verify();
    }

    @Test
    void 서비스키가_없으면_TAGO를_호출하지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bindTo(builder).build();
        TagoBusArrivalClient client = new TagoBusArrivalClient(
                builder,
                new TagoBusArrivalProperties("http://tago.test/arrival", "")
        );

        assertThatThrownBy(() -> client.findArrivals("25", "node-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TAGO 서비스 키");
        server.verify();
    }
}
