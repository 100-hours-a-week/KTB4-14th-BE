package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.RegionResponse;
import com.audigo.domain.travel.dto.RequiredPlaceRequest;
import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.dto.TravelPreferenceRequest;
import com.audigo.domain.travel.entity.FoodType;
import com.audigo.domain.travel.entity.Place;
import com.audigo.domain.travel.entity.PlaceProvider;
import com.audigo.domain.travel.entity.TravelPlaceSource;
import com.audigo.domain.travel.entity.TravelPlanPlace;
import com.audigo.domain.travel.entity.Region;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPreference;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.travel.repository.RegionRepository;
import com.audigo.domain.travel.repository.PlaceRepository;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TravelPlanService {

    private final TravelPlanRepository travelPlanRepository;
    private final RegionRepository regionRepository;
    private final PlaceRepository placeRepository;

    public TravelPlanService(
            TravelPlanRepository travelPlanRepository,
            RegionRepository regionRepository,
            PlaceRepository placeRepository
    ) {
        this.travelPlanRepository = travelPlanRepository;
        this.regionRepository = regionRepository;
        this.placeRepository = placeRepository;
    }

    // 지역 풀네임 오름차순 정렬
    @Transactional(readOnly = true)
    public List<RegionResponse> getRegions() {
        return regionRepository.findAllByOrderByFullNameAsc().stream()
                .map(RegionResponse::from)
                .toList();
    }

    // 여행 생성하기 요청(1단계)
    @Transactional
    public TravelPlan createTravelPlan(Long userId, TravelPlanRequest request) {
        validateRequest(request);

        Region region = regionRepository.findById(request.regionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.REGION_NOT_FOUND));

        TravelPlan travelPlan = TravelPlan.create(
                userId,
                region,
                request.arrivalDatetime(),
                request.departureDatetime(),
                request.headcount(),
                request.companionType()
        );

        TravelPreference preference = createPreference(travelPlan, request.preference());
        travelPlan.attachPreference(preference);

        addRequiredPlaces(travelPlan, request.requiredPlaces());
        return travelPlanRepository.save(travelPlan);
    }

    private void addRequiredPlaces(TravelPlan travelPlan, List<RequiredPlaceRequest> requests) {
        validateRequiredPlaces(requests);
        for (int index = 0; index < requests.size(); index++) {
            RequiredPlaceRequest request = requests.get(index);
            PlaceProvider provider = request.provider();
            Place place = placeRepository.findByProviderAndProviderPlaceId(
                            provider,
                            request.providerPlaceId()
                    )
                    .orElseGet(() -> placeRepository.save(Place.create(
                            provider,
                            request.providerPlaceId()
                    )));

            travelPlan.addRequiredPlace(TravelPlanPlace.create(
                    travelPlan,
                    place,
                    request.placeType(),
                    TravelPlaceSource.USER_REQUIRED,
                    request.resolvedOrder(index)
            ));
        }
    }

    private void validateRequiredPlaces(List<RequiredPlaceRequest> requests) {
        Set<String> identifiers = new HashSet<>();
        for (RequiredPlaceRequest request : requests) {
            String identifier = request.provider() + ":" + request.providerPlaceId();
            if (!identifiers.add(identifier)) {
                throw new BusinessException(ErrorCode.DUPLICATED_REQUIRED_PLACE);
            }
        }
    }

    //2단계
    private TravelPreference createPreference(TravelPlan travelPlan, TravelPreferenceRequest request) {
        return TravelPreference.create(
                travelPlan,
                request.paceType(),
                request.transportType(),
                request.budgetMin(),
                request.budgetMax(),
                request.budgetType(),
                request.distancePreference(),
                request.themes(),
                request.foods(),
                request.extraRequest()
        );
    }

    // 도착시간이 출발시간보다 빠른지 확인
    private void validateRequest(TravelPlanRequest request) {
        if (request.arrivalDatetime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (request.preference() != null) {
            validateUniqueValues(request.preference().themes(), request.preference().foods());
        }
    }

    // 여행 테마와 음식 중복이 있는지 확인
    private void validateUniqueValues(List<TravelThemeType> themes, List<FoodType> foods) {
        if (hasDuplicates(themes) || hasDuplicates(foods)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    // hashSet과 list 크기 비교해서 중복 여부 판단
    private boolean hasDuplicates(List<?> values) {
        return values != null && new HashSet<>(values).size() != values.size();
    }

}
