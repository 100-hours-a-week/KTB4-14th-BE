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

    @Test
    void allows_three_rematches_and_exhausts_session_on_third_success() {
        MatchingRequest request = matchingRequest();

        request.completeRematch();
        request.completeRematch();
        request.completeRematch();

        assertThat(request.getRetryCount()).isEqualTo(3);
        assertThat(request.getRemainingRetryCount()).isZero();
        assertThat(request.canRematch()).isFalse();
        assertThat(request.getStatus()).isEqualTo(MatchingRequestStatus.EXHAUSTED);
    }

    @Test
    void exhausted_session_can_still_connect_to_last_candidates() {
        MatchingRequest request = matchingRequest();
        request.completeRematch();
        request.completeRematch();
        request.completeRematch();

        request.markConnected();

        assertThat(request.getStatus()).isEqualTo(MatchingRequestStatus.CONNECTED);
    }

    @Test
    void exhausted_session_can_be_cancelled_for_new_conditions() {
        MatchingRequest request = matchingRequest();
        request.completeRematch();
        request.completeRematch();
        request.completeRematch();

        request.cancel();

        assertThat(request.getStatus()).isEqualTo(MatchingRequestStatus.CANCELLED);
        assertThat(request.getRetryCount()).isEqualTo(3);
        assertThat(request.getEndedAt()).isNotNull();
    }

    @Test
    void connected_session_cannot_be_cancelled_by_new_conditions_action() {
        MatchingRequest request = matchingRequest();
        request.markConnected();

        assertThatThrownBy(request::cancel).isInstanceOf(IllegalStateException.class);
        assertThat(request.getStatus()).isEqualTo(MatchingRequestStatus.CONNECTED);
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
