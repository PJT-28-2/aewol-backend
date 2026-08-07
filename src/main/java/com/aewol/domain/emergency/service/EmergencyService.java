package com.aewol.domain.emergency.service;

import com.aewol.domain.emergency.dto.HospitalDetailResponse;
import com.aewol.domain.emergency.dto.HospitalResponse;

import java.util.List;

public interface EmergencyService {
    List<HospitalResponse> searchNearby(double latitude, double longitude, double radiusKm, boolean is24h);

    HospitalDetailResponse getDetail(Long hospitalId);
}
