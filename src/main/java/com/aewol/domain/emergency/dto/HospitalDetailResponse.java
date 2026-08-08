package com.aewol.domain.emergency.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class HospitalDetailResponse {
    private Long hospitalId;
    private String name;
    private String address;
    private String phone;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private Boolean is24h;
    private Boolean isHolidayOpen;
    private Integer avgWaitMinutes;
    private LocalDateTime updatedAt;
}
