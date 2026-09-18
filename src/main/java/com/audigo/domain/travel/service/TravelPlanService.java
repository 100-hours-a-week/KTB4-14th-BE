package com.audigo.domain.travel.service;

import com.audigo.domain.travel.dto.RegionResponse;
import com.audigo.domain.travel.dto.TravelPlanRequest;
import com.audigo.domain.travel.dto.TravelPreferenceRequest;
import com.audigo.domain.travel.entity.FoodType;
import com.audigo.domain.travel.entity.Region;
import com.audigo.domain.travel.entity.TravelPlan;
import com.audigo.domain.travel.entity.TravelPreference;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.travel.repository.RegionRepository;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TravelPlanService {

    private final TravelPlanRepository travelPlanRepository;
    private final RegionRepository regionRepository;

    public TravelPlanService(
            TravelPlanRepository travelPlanRepository,
            RegionRepository regionRepository
    ) {
        this.travelPlanRepository = travelPlanRepository;
        this.regionRepository = regionRepository;
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
        return travelPlanRepository.save(travelPlan);
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

    private void validateUniqueValues(List<TravelThemeType> themes, List<FoodType> foods) {
        if (hasDuplicates(themes) || hasDuplicates(foods)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private boolean hasDuplicates(List<?> values) {
        return values != null && new HashSet<>(values).size() != values.size();
    }

}
