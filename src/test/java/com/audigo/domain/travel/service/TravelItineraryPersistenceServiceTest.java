package com.audigo.domain.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.entity.ItineraryDay;
import com.audigo.domain.travel.entity.ItineraryItem;
import com.audigo.domain.travel.entity.Place;
import com.audigo.domain.travel.entity.PlaceProvider;
import com.audigo.domain.travel.entity.PlaceType;
import com.audigo.domain.travel.entity.RouteSegment;
import com.audigo.domain.travel.entity.TravelGenerationJob;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPlanPlace;
import com.audigo.domain.travel.entity.TravelTransportType;
import com.audigo.domain.travel.repository.ItineraryDayRepository;
import com.audigo.domain.travel.repository.ItineraryItemRepository;
import com.audigo.domain.travel.repository.PlaceRepository;
import com.audigo.domain.travel.repository.RouteSegmentRepository;
import com.audigo.domain.travel.repository.TravelPlanPlaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TravelItineraryPersistenceServiceTest {

    @Mock
    private ItineraryDayRepository dayRepository;

    @Mock
    private ItineraryItemRepository itemRepository;

    @Mock
    private RouteSegmentRepository routeRepository;

    @Mock
    private TravelPlanPlaceRepository planPlaceRepository;

    @Mock
    private PlaceRepository placeRepository;

    private TravelGenerationProgressStore progressStore;
    private TravelItineraryMetadataStore metadataStore;
    private TravelItineraryPersistenceService service;

    @BeforeEach
    void setUp() {
        progressStore = new TravelGenerationProgressStore();
        metadataStore = new TravelItineraryMetadataStore();
        service = new TravelItineraryPersistenceService(
                new ObjectMapper(),
                progressStore,
                dayRepository,
                itemRepository,
                routeRepository,
                planPlaceRepository,
                placeRepository,
                metadataStore
        );
        when(routeRepository.findAllByTravelPlanId(55L)).thenReturn(List.of());
        when(dayRepository.findAllByTravelPlanIdOrderByDayNumberAsc(55L)).thenReturn(List.of());
    }

    @Test
    void route_segments만_있어도_필수장소로_일정을_구성하고_order와_AI_도착정보를_보존한다() {
        TravelPlan plan = mock(TravelPlan.class);
        TravelGenerationJob job = mock(TravelGenerationJob.class);
        TravelPlanPlace firstPlace = planPlace(11L, "place-1", 1);
        TravelPlanPlace secondPlace = planPlace(12L, "place-2", 2);
        ItineraryDay day = mock(ItineraryDay.class);
        ItineraryItem firstItem = item(101L);
        ItineraryItem secondItem = item(102L);
        RouteSegment savedRoute = mock(RouteSegment.class);

        when(job.getId()).thenReturn(1L);
        when(job.getTravelPlan()).thenReturn(plan);
        when(plan.getId()).thenReturn(55L);
        when(plan.getArrivalDatetime()).thenReturn(LocalDateTime.of(2026, 9, 22, 10, 0));
        when(plan.getDepartureDatetime()).thenReturn(LocalDateTime.of(2026, 9, 23, 18, 0));
        when(plan.getRequiredPlaces()).thenReturn(List.of(firstPlace, secondPlace));
        when(planPlaceRepository.findAllByTravelPlanIdOrderByPlaceOrderAsc(55L))
                .thenReturn(List.of(firstPlace, secondPlace));
        when(dayRepository.save(any(ItineraryDay.class))).thenReturn(day);
        when(itemRepository.save(any(ItineraryItem.class))).thenReturn(firstItem, secondItem);
        when(routeRepository.save(any(RouteSegment.class))).thenReturn(savedRoute);
        when(savedRoute.getId()).thenReturn(301L);

        progressStore.update(1L,
                com.audigo.domain.travel.entity.TravelGenerationStage.ROUTE_OPTIMIZE,
                com.audigo.domain.travel.entity.TravelGenerationStageState.DONE,
                """
                {"result":{"route_segments":[
                  {"day_number":1,"from_sequence":1,"to_sequence":2,
                   "transport_type":"PUBLIC_TRANSPORT","duration_minutes":35,
                   "distance_meter":8000,"cost":1400,"order":1,
                   "line_name":"2호선","vehicle_number":"내선","next_arrival_minutes":8,
                   "estimated_arrival_at":"2026-09-22T12:35:00"}
                ]}}
                """);

        assertThat(service.persistIfPresent(job)).isTrue();

        ArgumentCaptor<RouteSegment> routeCaptor = ArgumentCaptor.forClass(RouteSegment.class);
        org.mockito.Mockito.verify(routeRepository).save(routeCaptor.capture());
        RouteSegment route = routeCaptor.getValue();
        assertThat(route.getTransportType()).isEqualTo(TravelTransportType.PUBLIC_TRANSPORT);
        assertThat(route.getOrder()).isEqualTo(1);
        TravelItineraryMetadataStore.RouteMetadata metadata = metadataStore.route(301L);
        assertThat(metadata.nextArrivalMinutes()).isEqualTo(8);
        assertThat(metadata.lineName()).isEqualTo("2호선");
        assertThat(metadata.realtime()).isFalse();
    }

    @Test
    void 최신_AI_응답의_category와_route_from_previous를_일정과_버스경로로_저장한다() {
        TravelPlan plan = mock(TravelPlan.class);
        TravelGenerationJob job = mock(TravelGenerationJob.class);
        ItineraryDay day = mock(ItineraryDay.class);
        ItineraryItem firstItem = item(101L);
        ItineraryItem secondItem = item(102L);
        RouteSegment savedRoute = mock(RouteSegment.class);
        Place firstPlace = Place.create(PlaceProvider.KAKAO, "mock-place-1");
        Place secondPlace = Place.create(PlaceProvider.KAKAO, "mock-place-2");
        TravelPlanPlace firstPlanPlace = mock(TravelPlanPlace.class);
        TravelPlanPlace secondPlanPlace = mock(TravelPlanPlace.class);

        when(job.getId()).thenReturn(1L);
        when(job.getTravelPlan()).thenReturn(plan);
        when(plan.getId()).thenReturn(55L);
        when(plan.getArrivalDatetime()).thenReturn(LocalDateTime.of(2026, 9, 24, 10, 0));
        when(plan.getDepartureDatetime()).thenReturn(LocalDateTime.of(2026, 9, 26, 18, 0));
        when(plan.getRequiredPlaces()).thenReturn(List.of());
        when(planPlaceRepository.findAllByTravelPlanIdOrderByPlaceOrderAsc(55L)).thenReturn(List.of());
        when(placeRepository.findByProviderAndProviderPlaceId(PlaceProvider.KAKAO, "mock-place-1"))
                .thenReturn(Optional.of(firstPlace));
        when(placeRepository.findByProviderAndProviderPlaceId(PlaceProvider.KAKAO, "mock-place-2"))
                .thenReturn(Optional.of(secondPlace));
        when(firstPlanPlace.getId()).thenReturn(11L);
        when(secondPlanPlace.getId()).thenReturn(12L);
        when(planPlaceRepository.save(any())).thenReturn(firstPlanPlace, secondPlanPlace);
        when(dayRepository.save(any(ItineraryDay.class))).thenReturn(day);
        when(itemRepository.save(any(ItineraryItem.class))).thenReturn(firstItem, secondItem);
        when(routeRepository.save(any(RouteSegment.class))).thenReturn(savedRoute);
        when(savedRoute.getId()).thenReturn(301L);

        progressStore.update(1L,
                com.audigo.domain.travel.entity.TravelGenerationStage.ROUTE_OPTIMIZE,
                com.audigo.domain.travel.entity.TravelGenerationStageState.DONE,
                """
                {
                  "generation_job_id": 1,
                  "travel_plan_id": 55,
                  "title": "서울 버스 여행",
                  "days": [{
                    "day_number": 1,
                    "travel_date": "2026-09-24",
                    "items": [
                      {
                        "provider": "KAKAO",
                        "provider_place_id": "mock-place-1",
                        "place_name": "Mock 장소 1",
                        "address": "지번 주소",
                        "road_address": "도로명 주소",
                        "latitude": 37.5665,
                        "longitude": 126.9780,
                        "category": "관광",
                        "sequence": 1,
                        "item_type": "TOUR",
                        "start_time": "10:00",
                        "end_time": "11:00"
                      },
                      {
                        "provider": "KAKAO",
                        "provider_place_id": "mock-place-2",
                        "place_name": "Mock 장소 2",
                        "address": "지번 주소 2",
                        "road_address": "도로명 주소 2",
                        "latitude": 37.5700,
                        "longitude": 126.9850,
                        "category": "관광",
                        "sequence": 2,
                        "item_type": "TOUR",
                        "start_time": "12:00",
                        "end_time": "13:00",
                        "route_from_previous": {
                          "transport_type": "BUS",
                          "duration_minutes": 35,
                          "distance_meter": 8000,
                          "line_name": "간선버스",
                          "vehicle_number": "701",
                          "legs": [{
                            "mode": "BUS",
                            "line_name": "간선버스",
                            "vehicle_number": "701"
                          }]
                        }
                      }
                    ]
                  }]
                }
                """);

        assertThat(service.persistIfPresent(job)).isTrue();

        ArgumentCaptor<RouteSegment> routeCaptor = ArgumentCaptor.forClass(RouteSegment.class);
        org.mockito.Mockito.verify(routeRepository).save(routeCaptor.capture());
        assertThat(routeCaptor.getValue().getTransportType()).isEqualTo(TravelTransportType.PUBLIC_TRANSPORT);
        assertThat(routeCaptor.getValue().getOrder()).isEqualTo(1);
        assertThat(metadataStore.place(102L).address()).isEqualTo("도로명 주소 2");
        assertThat(metadataStore.route(301L).lineName()).isEqualTo("간선버스");
        assertThat(metadataStore.route(301L).vehicleNumber()).isEqualTo("701");
    }

    private TravelPlanPlace planPlace(Long id, String providerPlaceId, int order) {
        TravelPlanPlace planPlace = mock(TravelPlanPlace.class);
        Place place = Place.create(PlaceProvider.KAKAO, providerPlaceId);
        when(planPlace.getId()).thenReturn(id);
        when(planPlace.getPlace()).thenReturn(place);
        when(planPlace.getPlaceType()).thenReturn(PlaceType.TOURISM);
        return planPlace;
    }

    private ItineraryItem item(Long id) {
        ItineraryItem item = mock(ItineraryItem.class);
        when(item.getId()).thenReturn(id);
        return item;
    }
}
