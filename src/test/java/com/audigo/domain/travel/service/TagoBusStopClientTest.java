package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.MockRestServiceServer.bindTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.audigo.domain.travel.config.TagoBusStopProperties;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class TagoBusStopClientTest {

    @Test
    void 도시코드와_정류소_후보를_파싱한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bindTo(builder).build();
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/stops/getCtyCodeList");
                    var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
                    assertThat(query.getFirst("_type")).isEqualTo("json");
                    assertThat(query.getFirst("serviceKey")).isEqualTo("test-key");
                })
                .andRespond(withSuccess(
                        """
                        {
                          "response": {
                            "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE"},
                            "body": {"items": {"item": {"citycode": 25, "cityname": "대전광역시"}}}
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/stops/getSttnNoList");
                    var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
                    assertThat(query.getFirst("cityCode")).isEqualTo("25");
                    assertThat(URLDecoder.decode(query.getFirst("nodeNm"), StandardCharsets.UTF_8))
                            .isEqualTo("서부소방서");
                })
                .andRespond(withSuccess(
                        """
                        {
                          "response": {
                            "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE"},
                            "body": {"items": {"item": [
                              {"citycode": 25, "nodeid": "DJB8002544", "nodenm": "서부소방서", "nodeno": "1234"}
                            ]}}
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        TagoBusStopClient client = new TagoBusStopClient(
                builder,
                new TagoBusStopProperties("http://tago.test/stops", "test-key")
        );

        List<TagoBusStopClient.City> cities = client.findCities();
        List<TagoBusStopClient.Stop> stops = client.findStopsByName("25", "서부소방서");

        assertThat(cities).containsExactly(new TagoBusStopClient.City("25", "대전광역시"));
        assertThat(stops).containsExactly(
                new TagoBusStopClient.Stop("25", "DJB8002544", "서부소방서", "1234")
        );
        server.verify();
    }

    @Test
    void 서비스키가_없으면_TAGO를_호출하지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bindTo(builder).build();
        TagoBusStopClient client = new TagoBusStopClient(
                builder,
                new TagoBusStopProperties("http://tago.test/stops", "")
        );

        assertThatThrownBy(client::findCities)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TAGO 서비스 키");
        server.verify();
    }
}
