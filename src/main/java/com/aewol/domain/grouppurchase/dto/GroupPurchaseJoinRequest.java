package com.aewol.domain.grouppurchase.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class GroupPurchaseJoinRequest {

    private String recipientName;
    private String recipientPhone;
    private String zipCode;
    private String address;
    private String addressDetail;
}
