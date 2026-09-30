package com.audigo.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.audigo.domain.travel.entity.TravelPlanStatus;
import com.audigo.domain.travel.repository.TravelPlanRepository;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TravelPlanRepository travelPlanRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void counts_my_page_travels_from_confirmed_completed_plans() {
        User user = User.create("윤혁", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(travelPlanRepository.countByUserIdAndStatusAndConfirmedAtIsNotNullAndDepartureDatetimeLessThan(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(TravelPlanStatus.COMPLETED),
                any(LocalDateTime.class)
        )).thenReturn(2L);
        when(travelPlanRepository.countByUserIdAndStatusAndConfirmedAtIsNotNullAndDepartureDatetimeGreaterThanEqual(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(TravelPlanStatus.COMPLETED),
                any(LocalDateTime.class)
        )).thenReturn(3L);

        var response = userService.getMyPage(1L);

        assertThat(response.completedTravelCount()).isEqualTo(2L);
        assertThat(response.upcomingTravelCount()).isEqualTo(3L);
    }
}
