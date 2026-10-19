package com.audigo.domain.matching.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.user.entity.User;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchingRequestTest {

    @Test
    void starts_as_active_session_without_retries() {
        MatchingRequest request = matchingRequest();

        assertThat(request.getStatus()).isEqualTo(MatchingRequestStatus.ACTIVE);
        assertThat(request.getRetryCount()).isZero();
        assertThat(request.getEndedAt()).isNull();
    }

    @Test
    void finishes_session_when_connection_is_created() {
        MatchingRequest request = matchingRequest();

        request.markConnected();

        assertThat(request.getStatus()).isEqualTo(MatchingRequestStatus.CONNECTED);
        assertThat(request.getEndedAt()).isNotNull();
    }

    @Test
    void finishes_session_when_request_is_cancelled() {
        MatchingRequest request = matchingRequest();

        request.cancel();

        assertThat(request.getStatus()).isEqualTo(MatchingRequestStatus.CANCELLED);
        assertThat(request.getEndedAt()).isNotNull();
    }

    @Test
    void finishes_session_when_retries_are_exhausted() {
        MatchingRequest request = matchingRequest();

        request.markExhausted();

        assertThat(request.getStatus()).isEqualTo(MatchingRequestStatus.EXHAUSTED);
        assertThat(request.getEndedAt()).isNotNull();
    }

    @Test
    void rejects_transition_from_finished_session() {
        MatchingRequest request = matchingRequest();
        request.cancel();

        assertThatThrownBy(request::markExhausted)
                .isInstanceOf(IllegalStateException.class);
    }

    private MatchingRequest matchingRequest() {
        return MatchingRequest.create(
                User.create("요청자", null),
                PreferredCompanionGender.ANY,
                TravelPaceType.BALANCED,
                100_000,
                800_000,
                List.of(TravelThemeType.NATURE)
        );
    }
}
