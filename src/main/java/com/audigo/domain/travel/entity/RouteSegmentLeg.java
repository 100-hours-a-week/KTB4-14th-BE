package com.audigo.domain.travel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 하나의 route segment를 구성하는 세부 이동 구간이다.
 *
 * <p>AI 응답의 legs 배열을 그대로 보존하되, 버스·지하철 식별자는
 * 백엔드의 공개 계약인 bus_number·subway_line으로 정규화해서 저장한다.</p>
 */
@Entity
@Table(
        name = "route_segment_legs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_route_segment_leg_sequence",
                columnNames = {"route_segment_id", "sequence"}
        )
)
public class RouteSegmentLeg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_segment_id", nullable = false)
    private RouteSegment routeSegment;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "mode", nullable = false, length = 30)
    private String mode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bus_numbers", nullable = false, columnDefinition = "json")
    private List<String> busNumbers = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "subway_lines", nullable = false, columnDefinition = "json")
    private List<String> subwayLines = new ArrayList<>();

    @Column(name = "boarding_stop_name")
    private String boardingStopName;

    @Column(name = "boarding_station_number")
    private String boardingStationNumber;

    @Column(name = "alighting_stop_name")
    private String alightingStopName;

    @Column(name = "alighting_station_number")
    private String alightingStationNumber;

    @Column(name = "duration_minute")
    private Integer durationMinute;

    @Column(name = "distance_meter")
    private Integer distanceMeter;

    protected RouteSegmentLeg() {
    }

    private RouteSegmentLeg(
            RouteSegment routeSegment,
            int sequence,
            String mode,
            List<String> busNumbers,
            List<String> subwayLines,
            String boardingStopName,
            String boardingStationNumber,
            String alightingStopName,
            String alightingStationNumber,
            Integer durationMinute,
            Integer distanceMeter
    ) {
        this.routeSegment = Objects.requireNonNull(routeSegment, "경로 구간은 필수입니다.");
        if (sequence < 1) {
            throw new IllegalArgumentException("leg 순서는 1 이상이어야 합니다.");
        }
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("leg 이동 수단은 필수입니다.");
        }
        if (durationMinute != null && durationMinute < 0) {
            throw new IllegalArgumentException("leg 이동 시간은 음수일 수 없습니다.");
        }
        if (distanceMeter != null && distanceMeter < 0) {
            throw new IllegalArgumentException("leg 이동 거리는 음수일 수 없습니다.");
        }
        this.sequence = sequence;
        this.mode = mode.trim().toUpperCase(Locale.ROOT);
        this.busNumbers = normalizeValues(busNumbers);
        this.subwayLines = normalizeValues(subwayLines);
        this.boardingStopName = normalizeNullable(boardingStopName);
        this.boardingStationNumber = normalizeNullable(boardingStationNumber);
        this.alightingStopName = normalizeNullable(alightingStopName);
        this.alightingStationNumber = normalizeNullable(alightingStationNumber);
        this.durationMinute = durationMinute;
        this.distanceMeter = distanceMeter;
    }

    public static RouteSegmentLeg create(
            RouteSegment routeSegment,
            int sequence,
            String mode,
            List<String> busNumbers,
            List<String> subwayLines,
            String boardingStopName,
            String boardingStationNumber,
            String alightingStopName,
            String alightingStationNumber,
            Integer durationMinute,
            Integer distanceMeter
    ) {
        return new RouteSegmentLeg(
                routeSegment,
                sequence,
                mode,
                busNumbers,
                subwayLines,
                boardingStopName,
                boardingStationNumber,
                alightingStopName,
                alightingStationNumber,
                durationMinute,
                distanceMeter
        );
    }

    private static List<String> normalizeValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String candidate = normalizeNullable(value);
            if (candidate != null && !normalized.contains(candidate)) {
                normalized.add(candidate);
            }
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public Long getId() {
        return id;
    }

    public RouteSegment getRouteSegment() {
        return routeSegment;
    }

    public int getSequence() {
        return sequence;
    }

    public String getMode() {
        return mode;
    }

    public List<String> getBusNumbers() {
        return busNumbers == null ? List.of() : Collections.unmodifiableList(busNumbers);
    }

    public List<String> getSubwayLines() {
        return subwayLines == null ? List.of() : Collections.unmodifiableList(subwayLines);
    }

    public String getBoardingStopName() {
        return boardingStopName;
    }

    public String getBoardingStationNumber() {
        return boardingStationNumber;
    }

    public String getAlightingStopName() {
        return alightingStopName;
    }

    public String getAlightingStationNumber() {
        return alightingStationNumber;
    }

    public Integer getDurationMinute() {
        return durationMinute;
    }

    public Integer getDistanceMeter() {
        return distanceMeter;
    }
}
