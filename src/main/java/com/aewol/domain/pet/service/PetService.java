package com.aewol.domain.pet.service;

import com.aewol.domain.pet.dto.PetCreateRequest;
import com.aewol.domain.pet.dto.PetResponse;
import com.aewol.domain.pet.dto.PetDocumentResponse;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;
import java.util.List;

public interface PetService {
    PetResponse createPet(String memberId, PetCreateRequest request);
    List<PetResponse> getPetsByMember(String memberId);
    PetResponse getPet(String memberId, String petId);
    void updatePet(String memberId, String petId, PetCreateRequest request);
    void deactivatePet(String memberId, String petId);
    PetDocumentResponse uploadVaccinationDocument(String memberId, String petId,
                                                   MultipartFile file, LocalDate issuedDate);
    List<PetDocumentResponse> getPetDocuments(String memberId, String petId);
    void deletePetDocument(String memberId, String petId, String docId);
}
