package com.audigo.domain.travel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;

@Entity
@Table(
        name = "travel_preference_foods",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_travel_preference_food",
                columnNames = {"travel_preference_id", "food_type"}
        )
)
public class TravelPreferenceFood {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "travel_preference_id", nullable = false)
    private TravelPreference travelPreference;

    @Enumerated(EnumType.STRING)
    @Column(name = "food_type", nullable = false, length = 30)
    private FoodType foodType;

    protected TravelPreferenceFood() {
    }

    private TravelPreferenceFood(TravelPreference travelPreference, FoodType foodType) {
        this.travelPreference = Objects.requireNonNull(travelPreference, "여행 취향은 필수입니다.");
        this.foodType = Objects.requireNonNull(foodType, "음식 선호는 필수입니다.");
    }

    static TravelPreferenceFood create(TravelPreference travelPreference, FoodType foodType) {
        return new TravelPreferenceFood(travelPreference, foodType);
    }

    public FoodType getFoodType() {
        return foodType;
    }
}
