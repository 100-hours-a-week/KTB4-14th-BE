package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.dto.ItineraryCompletionResponse;
import com.audigo.domain.travel.dto.ItineraryResponse;
import com.audigo.domain.travel.entity.ItineraryDay;
import com.audigo.domain.travel.entity.ItineraryItem;
import com.audigo.domain.travel.entity.Region;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.RouteSegmentLeg;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.audigo.domain.travel.repository.ItineraryDayRepository;
import com.audigo.domain.travel.repository.ItineraryItemRepository;
import com.audigo.domain.travel.repository.RouteSegmentRepository;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import com.audigo.global.error.BusinessException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
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
    private BusArrivalRealtimeService busArrivalRealtimeService;

    @Mock
    private TagoBusStopLookupService tagoBusStopLookupService;

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
    void 완료_처리하면_완료상태만_저장하고_실시간_갱신은_호출하지_않는다() {
        Long itemId = 101L;
        ItineraryItem item = completedItem(itemId, false, null);
        givenOwnedCompletedItem(itemId, item);
        doAnswer(invocation -> {
            when(item.isCompleted()).thenReturn(true);
            when(item.getCompletedAt()).thenReturn(invocation.getArgument(1));
            return null;
        }).when(item).updateCompletion(anyBoolean(), any());

        ItineraryCompletionResponse response = service.updateCompletion(7L, itemId, true);

        assertThat(response.completed()).isTrue();
        assertThat(response.completedAt()).isNotNull();
        verifyNoInteractions(routeRepository, realtimeService);
    }

    @Test
    void 경로_재계산은_현재시각으로_대중교통_경로만_갱신한다() {
        TravelPlan plan = mock(TravelPlan.class);
        when(plan.getStatus()).thenReturn(TravelPlanStatus.COMPLETED);
        when(travelPlanRepository.findByIdAndUserId(55L, 7L)).thenReturn(Optional.of(plan));

        RouteSegment publicRoute = route(201L, TravelTransportType.PUBLIC_TRANSPORT, 101L, 102L);
        RouteSegment walkRoute = route(202L, TravelTransportType.WALK, 102L, 103L);
        when(routeRepository.findAllByTravelPlanId(55L)).thenReturn(List.of(publicRoute, walkRoute));

        service.recalculateRoutes(7L, 55L);

        verify(realtimeService).refresh(eq(publicRoute), any(LocalDateTime.class));
        verify(realtimeService, never()).refresh(eq(walkRoute), any(LocalDateTime.class));
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

    @Test
    void 일정조회는_TAGO_실시간정보를_호출하지_않고_기본값을_반환한다() {
        TravelPlan plan = mock(TravelPlan.class);
        Region region = mock(Region.class);
        ItineraryDay day = mock(ItineraryDay.class);
        RouteSegment route = mock(RouteSegment.class);
        ItineraryItem from = mock(ItineraryItem.class);
        ItineraryItem to = mock(ItineraryItem.class);
        RouteSegmentLeg busLeg = mock(RouteSegmentLeg.class);
        RouteSegmentLeg walkLeg = mock(RouteSegmentLeg.class);

        when(travelPlanRepository.findByIdAndUserId(55L, 7L)).thenReturn(Optional.of(plan));
        when(plan.getId()).thenReturn(55L);
        when(plan.getStatus()).thenReturn(TravelPlanStatus.COMPLETED);
        when(plan.getRegion()).thenReturn(region);
        when(region.getFullName()).thenReturn("대전광역시 서구");
        when(plan.getArrivalDatetime()).thenReturn(LocalDateTime.of(2026, 9, 29, 9, 0));
        when(plan.getDepartureDatetime()).thenReturn(LocalDateTime.of(2026, 10, 1, 18, 0));
        LocalDateTime confirmedAt = LocalDateTime.of(2026, 9, 28, 12, 0);
        when(plan.getConfirmedAt()).thenReturn(confirmedAt);

        when(day.getId()).thenReturn(1L);
        when(day.getDayNumber()).thenReturn(1);
        when(day.getTravelDate()).thenReturn(LocalDate.of(2026, 9, 29));
        when(dayRepository.findAllByTravelPlanIdOrderByDayNumberAsc(55L)).thenReturn(List.of(day));
        when(itemRepository.findAllWithPlaceByTravelPlanIdOrderByDayNumberAndSequence(55L)).thenReturn(List.of());

        when(route.getId()).thenReturn(201L);
        when(route.getFromItineraryItem()).thenReturn(from);
        when(route.getToItineraryItem()).thenReturn(to);
        when(from.getId()).thenReturn(101L);
        when(from.getItineraryDay()).thenReturn(day);
        when(to.getId()).thenReturn(102L);
        when(route.getTransportType()).thenReturn(TravelTransportType.PUBLIC_TRANSPORT);
        when(route.getDurationMinutes()).thenReturn(20);
        when(route.getDistanceMeter()).thenReturn(1000);
        when(route.getTotalFareAmount()).thenReturn(1500);
        when(route.getOrder()).thenReturn(1);
        when(route.getLegs()).thenReturn(List.of(busLeg, walkLeg));
        when(routeRepository.findAllWithLegsByTravelPlanId(55L)).thenReturn(List.of(route));

        when(busLeg.getSequence()).thenReturn(1);
        when(busLeg.getMode()).thenReturn("BUS");
        when(busLeg.getBusNumbers()).thenReturn(List.of("115"));
        when(walkLeg.getSequence()).thenReturn(2);
        when(walkLeg.getMode()).thenReturn("WALK");
        when(walkLeg.getBusNumbers()).thenReturn(List.of());

        ItineraryResponse response = service.getItinerary(7L, 55L);

        assertThat(response.confirmedAt()).isEqualTo(confirmedAt);
        ItineraryResponse.RouteSegmentResponse routeResponse = response.days().get(0).routes().get(0);
        assertThat(routeResponse.realtime()).isFalse();
        assertThat(routeResponse.nextArrivalMinutes()).isNull();
        assertThat(routeResponse.realtimeMessage()).isNull();
        assertThat(routeResponse.legs().get(0).realtime()).isFalse();
        assertThat(routeResponse.legs().get(0).remainingStops()).isNull();
        assertThat(routeResponse.legs().get(1).realtime()).isFalse();
        verifyNoInteractions(tagoBusStopLookupService, busArrivalRealtimeService);
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
        lenient().when(route.getId()).thenReturn(id);
        lenient().when(route.getFromItineraryItem()).thenReturn(from);
        lenient().when(route.getToItineraryItem()).thenReturn(to);
        lenient().when(from.getId()).thenReturn(fromId);
        lenient().when(to.getId()).thenReturn(toId);
        lenient().when(route.getDurationMinutes()).thenReturn(null);
        lenient().when(route.getDistanceMeter()).thenReturn(null);
        lenient().when(route.getTotalFareAmount()).thenReturn(null);
        lenient().when(route.getOrder()).thenReturn(id.intValue());
        lenient().when(route.getLegs()).thenReturn(Collections.emptyList());
        return route;
    }
}
