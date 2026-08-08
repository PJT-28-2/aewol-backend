package com.aewol.domain.certificate.dto;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ApmsSyncRequest {
    @NotBlank
    private String regNumber;
    @NotBlank
    private String userName;
}
