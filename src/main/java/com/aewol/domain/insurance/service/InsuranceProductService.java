package com.aewol.domain.insurance.service;

import com.aewol.domain.insurance.dto.ProductResponse;
import java.util.List;

public interface InsuranceProductService {
    List<ProductResponse> getProducts(String petType, Integer age, String sort);
}
