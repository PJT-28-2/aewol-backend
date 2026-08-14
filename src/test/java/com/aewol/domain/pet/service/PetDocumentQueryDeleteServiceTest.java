package com.aewol.domain.pet.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.FileUtil;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.pet.dto.PetDocumentResponse;
import com.aewol.domain.pet.dto.PetRegistrationResponse;
import com.aewol.domain.pet.mapper.PetDocumentMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PetDocumentQueryDeleteServiceTest {

    @Mock PetMapper petMapper;
    @Mock PetDocumentMapper petDocumentMapper;
    @Mock FileUtil fileUtil;
    @Mock FileStorage fileStorage;
    @Mock PetRegistrationService petRegistrationService;

    private PetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PetServiceImpl(petMapper, petDocumentMapper, fileUtil, fileStorage, petRegistrationService);
        lenient().when(fileStorage.signedUrl(anyString()))
                .thenAnswer(invocation -> "signed:" + invocation.getArgument(0));
    }

    @Test
    void should_returnDocuments_when_ownerRequestsList() {
        givenOwner();
        when(petDocumentMapper.findByPetId("pet-1")).thenReturn(List.of(
                document("2", "/uploads/pet-documents/new.pdf", LocalDate.of(2026, 8, 7)),
                document("1", "/uploads/pet-documents/old.png", LocalDate.of(2026, 8, 1))));

        List<PetDocumentResponse> result = service.getPetDocuments("member-1", "pet-1");

        assertEquals(2, result.size());
        assertEquals("2", result.get(0).getDocId());
        assertEquals("vaccination-certificate.pdf", result.get(0).getDocName());
        assertEquals("2026-08-07", result.get(0).getIssuedDate());
        assertEquals("signed:/uploads/pet-documents/new.pdf", result.get(0).getFileUrl());
        assertEquals("2026-08-07T10:00", result.get(0).getCreatedAt());
        verify(petDocumentMapper).findByPetId("pet-1");
    }

    @Test
    void should_returnEmptyList_when_petHasNoDocuments() {
        givenOwner();
        when(petDocumentMapper.findByPetId("pet-1")).thenReturn(List.of());

        assertEquals(List.of(), service.getPetDocuments("member-1", "pet-1"));
    }

    @Test
    void should_throwForbidden_when_nonOwnerRequestsList() {
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getPetDocuments("member-2", "pet-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verifyNoInteractions(petDocumentMapper);
    }

    @Test
    void should_throwNotFound_when_missingPetRequestsList() {
        when(petMapper.findById("pet-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getPetDocuments("member-1", "pet-404"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verifyNoInteractions(petDocumentMapper);
    }

    @Test
    void should_deleteRecordAndFile_when_ownerDeletesDocument() throws IOException {
        givenOwner();
        when(petDocumentMapper.findByIdAndPetIdForUpdate("doc-1", "pet-1"))
                .thenReturn(document("doc-1", "/uploads/pet-documents/file.pdf", null));
        when(petDocumentMapper.deleteByIdAndPetId("doc-1", "pet-1")).thenReturn(1);

        service.deletePetDocument("member-1", "pet-1", "doc-1");

        verify(petDocumentMapper).deleteByIdAndPetId("doc-1", "pet-1");
        verify(fileUtil).delete("/uploads/pet-documents/file.pdf");
    }

    @Test
    void should_keepDeleteResult_when_fileCleanupFailsAfterDatabaseDelete() throws IOException {
        givenOwner();
        when(petDocumentMapper.findByIdAndPetIdForUpdate("doc-1", "pet-1"))
                .thenReturn(document("doc-1", "/uploads/pet-documents/file.pdf", null));
        when(petDocumentMapper.deleteByIdAndPetId("doc-1", "pet-1")).thenReturn(1);
        doThrow(new IOException("disk error"))
                .when(fileUtil).delete("/uploads/pet-documents/file.pdf");

        assertDoesNotThrow(() -> service.deletePetDocument("member-1", "pet-1", "doc-1"));

        verify(fileUtil).delete("/uploads/pet-documents/file.pdf");
    }

    @Test
    void should_throwNotFound_when_documentDoesNotBelongToPet() {
        givenOwner();
        when(petDocumentMapper.findByIdAndPetIdForUpdate("doc-2", "pet-1")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deletePetDocument("member-1", "pet-1", "doc-2"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(petDocumentMapper, never()).deleteByIdAndPetId(anyString(), anyString());
        verifyNoInteractions(fileUtil);
    }

    @Test
    void should_throwForbidden_when_nonOwnerDeletesDocument() {
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deletePetDocument("member-2", "pet-1", "doc-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verifyNoInteractions(petDocumentMapper, fileUtil);
    }

    @Test
    void should_throwNotFound_when_missingPetDeletesDocument() {
        when(petMapper.findById("pet-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deletePetDocument("member-1", "pet-404", "doc-1"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verifyNoInteractions(petDocumentMapper, fileUtil);
    }

    private void givenOwner() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
    }

    private static Map<String, Object> pet(String memberId) {
        Map<String, Object> pet = new HashMap<>();
        pet.put("pet_id", "pet-1");
        pet.put("member_id", memberId);
        return pet;
    }

    private static Map<String, Object> document(String docId, String fileUrl, LocalDate issuedDate) {
        Map<String, Object> document = new HashMap<>();
        document.put("doc_id", docId);
        document.put("pet_id", "pet-1");
        document.put("doc_name", "vaccination-certificate.pdf");
        document.put("doc_type", "VACCINATION");
        document.put("file_url", fileUrl);
        document.put("issued_date", issuedDate);
        document.put("created_at", LocalDateTime.of(2026, 8, 7, 10, 0));
        return document;
    }

    @Test
    void should_returnDocumentResponse_when_docTypeIsNotRegistration() {
        givenOwner();
        when(petDocumentMapper.findByIdAndPetId("doc-1", "pet-1"))
                .thenReturn(document("doc-1", "/uploads/pet-documents/file.pdf", LocalDate.of(2026, 8, 7)));

        Object result = service.getPetDocument("member-1", "pet-1", "doc-1");

        assertInstanceOf(PetDocumentResponse.class, result);
        assertEquals("doc-1", ((PetDocumentResponse) result).getDocId());
        verifyNoInteractions(petRegistrationService);
    }

    @Test
    void should_delegateToRegistrationService_when_docTypeIsRegistration() {
        givenOwner();
        Map<String, Object> registrationDocument = document("doc-2", null, null);
        registrationDocument.put("doc_type", "REGISTRATION");
        when(petDocumentMapper.findByIdAndPetId("doc-2", "pet-1")).thenReturn(registrationDocument);
        PetRegistrationResponse expected = PetRegistrationResponse.builder().docId("doc-2").build();
        when(petRegistrationService.getDetail("pet-1", "doc-2")).thenReturn(expected);

        Object result = service.getPetDocument("member-1", "pet-1", "doc-2");

        assertSame(expected, result);
        verify(petRegistrationService).getDetail("pet-1", "doc-2");
        verifyNoInteractions(fileStorage);
    }

    @Test
    void should_throwNotFound_when_documentMissing_onGetDetail() {
        givenOwner();
        when(petDocumentMapper.findByIdAndPetId("doc-404", "pet-1")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getPetDocument("member-1", "pet-1", "doc-404"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verifyNoInteractions(petRegistrationService);
    }

    @Test
    void should_throwForbidden_when_nonOwnerRequestsDetail() {
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getPetDocument("member-2", "pet-1", "doc-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verifyNoInteractions(petDocumentMapper, petRegistrationService);
    }
}
