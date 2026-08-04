package com.aewol.domain.share.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ShareContributionResponse {
    private final String id;
    private final String name;
    private final BigDecimal amount;
    private final int percentage;
}
