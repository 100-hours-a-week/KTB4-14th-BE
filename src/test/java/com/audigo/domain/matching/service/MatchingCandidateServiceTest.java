package com.audigo.domain.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.matching.dto.MatchingCandidateAiRequest;
import com.audigo.domain.matching.dto.MatchingCandidateResponse;
import com.audigo.domain.matching.dto.MatchingCandidatesResponse;
import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.PreferredCompanionGender;
import com.audigo.domain.matching.repository.MatchingRequestRepository;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.user.entity.User;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatchingCandidateServiceTest {

    @Mock
    private MatchingRequestRepository matchingRequestRepository;

    @Mock
    private MatchingAiClient matchingAiClient;

    @InjectMocks
    private MatchingCandidateService matchingCandidateService;

    @Test
    void gets_candidates_from_ai_with_my_matching_request() {
        MatchingRequest matchingRequest = matchingRequest();
        MatchingCandidatesResponse aiResponse = MatchingCandidatesResponse.of(List.of(
                new MatchingCandidateResponse(
                        22L,
                        "매칭후보",
                        "https://example.com/profile.png",
                        List.of(TravelThemeType.NATURE),
                        TravelPaceType.BALANCED,
                        89
                )
        ));
        when(matchingRequestRepository.findByRequester_Id(1L)).thenReturn(Optional.of(matchingRequest));
        when(matchingAiClient.getCandidates(any(MatchingCandidateAiRequest.class))).thenReturn(aiResponse);

        MatchingCandidatesResponse response = matchingCandidateService.getCandidates(1L);

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.candidates().getFirst().nickname()).isEqualTo("매칭후보");
        ArgumentCaptor<MatchingCandidateAiRequest> requestCaptor =
                ArgumentCaptor.forClass(MatchingCandidateAiRequest.class);
        verify(matchingAiClient).getCandidates(requestCaptor.capture());
        assertThat(requestCaptor.getValue().preferredCompanionGender()).isEqualTo(PreferredCompanionGender.FEMALE);
        assertThat(requestCaptor.getValue().themes()).containsExactly(TravelThemeType.NATURE, TravelThemeType.FOOD);
        assertThat(requestCaptor.getValue().pace()).isEqualTo(TravelPaceType.BALANCED);
    }

    @Test
    void rejects_candidates_when_matching_request_not_found() {
        when(matchingRequestRepository.findByRequester_Id(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> matchingCandidateService.getCandidates(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCHING_REQUEST_NOT_FOUND)
                );
    }

    private MatchingRequest matchingRequest() {
        return MatchingRequest.create(
                User.create("윤혁", null),
                PreferredCompanionGender.FEMALE,
                TravelPaceType.BALANCED,
                100_000,
                800_000,
                List.of(TravelThemeType.NATURE, TravelThemeType.FOOD)
        );
    }
}
