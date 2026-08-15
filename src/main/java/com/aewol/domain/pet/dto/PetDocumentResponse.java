package com.aewol.domain.pet.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PetDocumentResponse {
    private String docId;
    private String petId;
    private String docName;
    private String docType;
    private String fileUrl;
    private String issuedDate;
    private String createdAt;
}
