package com.audigo.domain.travel.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.dto.TravelPlanCreatedResponse;
import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.audigo.domain.travel.service.TravelGenerationV2Service;
import com.audigo.global.security.CurrentUser;
import org.junit.jupiter.api.Test;

class TravelPlanV2ControllerTest {

    @Test
    void 재생성_요청을_v2_SQS_접수_서비스로_전달한다() {
        CurrentUser currentUser = mock(CurrentUser.class);
        TravelGenerationV2Service generationService = mock(TravelGenerationV2Service.class);
        TravelPlanRequest request = mock(TravelPlanRequest.class);
        TravelPlanCreatedResponse expected = new TravelPlanCreatedResponse(
                10L,
                42L,
                TravelPlanStatus.GENERATING
        );
        when(currentUser.id()).thenReturn(1L);
        when(generationService.regenerate(1L, 10L, request)).thenReturn(expected);
        TravelPlanV2Controller controller = new TravelPlanV2Controller(currentUser, generationService);

        var response = controller.regenerate(10L, request);

        assertThat(response.data()).isEqualTo(expected);
        verify(generationService).regenerate(1L, 10L, request);
    }
}
