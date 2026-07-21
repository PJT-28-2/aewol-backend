package com.aewol.domain.emergency.service;

import com.aewol.domain.emergency.mapper.EmergencyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmergencyServiceImpl implements EmergencyService {

    private final EmergencyMapper emergencyMapper;

    @Override
    public List<Map<String, Object>> searchNearby(double latitude, double longitude, double radiusKm) {
        return emergencyMapper.findNearby(latitude, longitude, radiusKm);
    }

    @Override
    public List<Map<String, Object>> get24hHospitals() {
        return emergencyMapper.find24hHospitals();
    }
}
