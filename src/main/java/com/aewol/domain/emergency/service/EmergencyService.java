package com.aewol.domain.emergency.service;

import java.util.List;
import java.util.Map;

public interface EmergencyService {
    List<Map<String, Object>> searchNearby(double latitude, double longitude, double radiusKm);
    List<Map<String, Object>> get24hHospitals();
}
