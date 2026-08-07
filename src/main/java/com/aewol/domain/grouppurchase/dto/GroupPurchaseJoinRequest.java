package com.aewol.domain.grouppurchase.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class GroupPurchaseJoinRequest {

    @NotBlank
    @Size(max = 30)
    private String recipientName;

    @NotBlank
    @Size(max = 20)
    private String recipientPhone;

    @NotBlank
    @Size(max = 10)
    private String zipCode;

    @NotBlank
    @Size(max = 300)
    private String address;

    @NotBlank
    @Size(max = 100)
    private String addressDetail;
}
