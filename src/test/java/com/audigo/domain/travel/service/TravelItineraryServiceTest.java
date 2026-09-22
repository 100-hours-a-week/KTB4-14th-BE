package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.dto.ItineraryCompletionResponse;
import com.audigo.domain.travel.entity.ItineraryDay;
import com.audigo.domain.travel.entity.ItineraryItem;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.audigo.domain.travel.repository.ItineraryDayRepository;
import com.audigo.domain.travel.repository.ItineraryItemRepository;
import com.audigo.domain.travel.repository.RouteSegmentRepository;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import com.audigo.global.error.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TravelItineraryServiceTest {

    @Mock
    private TravelPlanRepository travelPlanRepository;

    @Mock
    private ItineraryDayRepository dayRepository;

    @Mock
    private ItineraryItemRepository itemRepository;

    @Mock
    private RouteSegmentRepository routeRepository;

    @Mock
    private PublicTransportRealtimeService realtimeService;

    @Mock
    private KakaoPlaceSearchService kakaoPlaceSearchService;

    private TravelItineraryService service;

    @BeforeEach
    void setUp() {
        service = new TravelItineraryService(
                travelPlanRepository,
                dayRepository,
                itemRepository,
                routeRepository,
                new TravelItineraryMetadataStore(),
                realtimeService,
                kakaoPlaceSearchService
        );
    }

    @Test
    void 이미_완료된_동일_요청은_완료시각과_실시간_API를_중복_변경하지_않는다() {
        Long itemId = 101L;
        LocalDateTime completedAt = LocalDateTime.of(2026, 9, 22, 11, 30);
        ItineraryItem item = completedItem(itemId, true, completedAt);
        givenOwnedCompletedItem(itemId, item);

        ItineraryCompletionResponse response = service.updateCompletion(7L, itemId, true);

        assertThat(response.completed()).isTrue();
        assertThat(response.completedAt()).isEqualTo(completedAt);
        verify(item, never()).updateCompletion(anyBoolean(), any());
        verifyNoInteractions(routeRepository, realtimeService);
    }

    @Test
    void 완료_처리하면_완료시각을_전달해_인접한_대중교통_경로만_갱신한다() {
        Long itemId = 101L;
        ItineraryItem item = completedItem(itemId, false, null);
        givenOwnedCompletedItem(itemId, item);
        when(item.getItineraryDay().getTravelPlan().getId()).thenReturn(55L);
        doAnswer(invocation -> {
            when(item.isCompleted()).thenReturn(true);
            when(item.getCompletedAt()).thenReturn(invocation.getArgument(1));
            return null;
        }).when(item).updateCompletion(anyBoolean(), any());

        RouteSegment publicAdjacent = route(201L, TravelTransportType.PUBLIC_TRANSPORT, itemId, 102L);
        RouteSegment walkAdjacent = route(202L, TravelTransportType.WALK, itemId, 103L);
        RouteSegment publicUnrelated = route(203L, TravelTransportType.PUBLIC_TRANSPORT, 999L, 1000L);
        when(routeRepository.findAllByTravelPlanId(55L))
                .thenReturn(List.of(publicAdjacent, walkAdjacent, publicUnrelated));

        ItineraryCompletionResponse response = service.updateCompletion(7L, itemId, true);

        assertThat(response.completed()).isTrue();
        assertThat(response.completedAt()).isNotNull();
        verify(realtimeService).refresh(publicAdjacent, response.completedAt());
        verify(realtimeService, never()).refresh(walkAdjacent, response.completedAt());
        verify(realtimeService, never()).refresh(publicUnrelated, response.completedAt());
    }

    @Test
    void 다른_사용자는_일정_완료를_처리할_수_없다() {
        when(itemRepository.findByIdAndItineraryDayTravelPlanUserId(101L, 999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCompletion(999L, 101L, true))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(realtimeService);
    }

    @Test
    void 다른_사용자는_일정을_조회할_수_없다() {
        when(travelPlanRepository.findByIdAndUserId(55L, 999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getItinerary(999L, 55L))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(dayRepository, routeRepository, realtimeService);
    }

    private void givenOwnedCompletedItem(Long itemId, ItineraryItem item) {
        when(itemRepository.findByIdAndItineraryDayTravelPlanUserId(itemId, 7L))
                .thenReturn(Optional.of(item));
        TravelPlan plan = item.getItineraryDay().getTravelPlan();
        when(plan.getStatus()).thenReturn(TravelPlanStatus.COMPLETED);
    }

    private ItineraryItem completedItem(Long itemId, boolean completed, LocalDateTime completedAt) {
        ItineraryItem item = mock(ItineraryItem.class);
        ItineraryDay day = mock(ItineraryDay.class);
        TravelPlan plan = mock(TravelPlan.class);
        when(item.getId()).thenReturn(itemId);
        when(item.isCompleted()).thenReturn(completed);
        when(item.getCompletedAt()).thenReturn(completedAt);
        when(item.getItineraryDay()).thenReturn(day);
        when(day.getTravelPlan()).thenReturn(plan);
        return item;
    }

    private RouteSegment route(Long id, TravelTransportType type, Long fromId, Long toId) {
        RouteSegment route = mock(RouteSegment.class);
        ItineraryItem from = mock(ItineraryItem.class);
        ItineraryItem to = mock(ItineraryItem.class);
        when(route.getTransportType()).thenReturn(type);
        if (type == TravelTransportType.PUBLIC_TRANSPORT) {
            lenient().when(route.getFromItineraryItem()).thenReturn(from);
            lenient().when(route.getToItineraryItem()).thenReturn(to);
            lenient().when(from.getId()).thenReturn(fromId);
            lenient().when(to.getId()).thenReturn(toId);
        }
        return route;
    }
}
