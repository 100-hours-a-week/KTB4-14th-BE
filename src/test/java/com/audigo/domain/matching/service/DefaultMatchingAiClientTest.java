package com.audigo.domain.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.audigo.domain.matching.config.MatchingAiProperties;
import com.audigo.domain.matching.dto.MatchingCandidateAiRequest;
import com.audigo.domain.matching.dto.MatchingCandidatesResponse;
import com.audigo.domain.matching.entity.PreferredCompanionGender;
import com.audigo.domain.travel.config.AiServerProperties;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DefaultMatchingAiClientTest {

    @Test
    void calls_ai_server_and_parses_candidates() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ai.test/api/ai/v1/matches/candidates"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-ai-token"))
                .andExpect(content().string(containsString("\"preferred_companion_gender\":\"FEMALE\"")))
                .andRespond(withSuccess("""
                        {
                          "candidates": [
                            {
                              "user_id": 22,
                              "nickname": "매칭후보",
                              "profile_image_url": "https://example.com/profile.png",
                              "theme": ["NATURE", "FOOD"],
                              "pace": "BALANCED",
                              "match_rate": 89
                            }
                          ],
                          "count": 1
                        }
                        """, MediaType.APPLICATION_JSON));
        DefaultMatchingAiClient client = new DefaultMatchingAiClient(
                builder.build(),
                aiProperties("http://ai.test", "test-ai-token"),
                new MatchingAiProperties("/api/ai/v1/matches/candidates", false, 3)
        );

        MatchingCandidatesResponse response = client.getCandidates(request());

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.candidates().getFirst().userId()).isEqualTo(22L);
        assertThat(response.candidates().getFirst().themes()).containsExactly(TravelThemeType.NATURE, TravelThemeType.FOOD);
        server.verify();
    }

    @Test
    void returns_mock_candidates_when_mock_enabled() {
        DefaultMatchingAiClient client = new DefaultMatchingAiClient(
                RestClient.builder().build(),
                aiProperties("", ""),
                new MatchingAiProperties("/api/ai/v1/matches/candidates", true, 2)
        );

        MatchingCandidatesResponse response = client.getCandidates(request());

        assertThat(response.count()).isEqualTo(2);
        assertThat(response.candidates()).extracting("nickname")
                .containsExactly("매칭 후보 1", "매칭 후보 2");
    }

    private MatchingCandidateAiRequest request() {
        return new MatchingCandidateAiRequest(
                10L,
                1L,
                PreferredCompanionGender.FEMALE,
                List.of(TravelThemeType.NATURE, TravelThemeType.FOOD),
                TravelPaceType.BALANCED,
                100_000,
                800_000
        );
    }

    private AiServerProperties aiProperties(String baseUrl, String apiToken) {
        return new AiServerProperties(
                baseUrl,
                "/api/ai/v1/itinerary-jobs/stream",
                apiToken,
                5,
                330,
                300
        );
    }
}
