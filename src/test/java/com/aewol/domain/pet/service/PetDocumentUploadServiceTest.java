package com.aewol.domain.pet.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.FileUtil;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.pet.dto.PetDocumentResponse;
import com.aewol.domain.pet.mapper.PetDocumentMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class PetDocumentUploadServiceTest {

    @Mock PetMapper petMapper;
    @Mock PetDocumentMapper petDocumentMapper;
    @Mock FileUtil fileUtil;
    @Mock FileStorage fileStorage;

    private PetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PetServiceImpl(petMapper, petDocumentMapper, fileUtil, fileStorage);
    }

    @Test
    void should_registerDocument_when_ownerUploadsFirstTime() throws IOException {
        givenOwner();
        MockMultipartFile file = jpeg();
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "VACCINATION")).thenReturn(null);
        when(fileUtil.upload(file, "pet-documents", "jpg")).thenReturn("/uploads/pet-documents/new.jpg");
        PetDocumentResponse response = service.uploadPetDocument("member-1", "pet-1", "VACCINATION", file,
                LocalDate.of(2026, 8, 1));

        assertEquals("VACCINATION", response.getDocType());
        assertEquals("certificate.jpeg", response.getDocName());
        assertEquals("/uploads/pet-documents/new.jpg", response.getFileUrl());
        verify(petDocumentMapper).insert(argThat(row ->
                "certificate.jpeg".equals(row.get("docName"))));
    }

    @Test
    void should_updateDocument_when_ownerReuploads() throws IOException {
        givenOwner();
        MockMultipartFile file = jpeg();
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "VACCINATION"))
                .thenReturn(document(7L, "/uploads/pet-documents/old.pdf"));
        when(fileUtil.upload(file, "pet-documents", "jpg")).thenReturn("/uploads/pet-documents/new.jpg");

        service.uploadPetDocument("member-1", "pet-1", "VACCINATION", file, null);

        verify(petDocumentMapper).update(argThat(row ->
                Long.valueOf(7L).equals(row.get("docId"))
                        && "certificate.jpeg".equals(row.get("docName"))));
        verify(petDocumentMapper, never()).insert(anyMap());
        verify(fileUtil).delete("/uploads/pet-documents/old.pdf");
    }

    @Test
    void should_replaceDocumentInformation_when_ownerReuploads() throws IOException {
        givenOwner();
        MockMultipartFile file = pdf("certificate.pdf", "application/pdf");
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "VACCINATION"))
                .thenReturn(document(7L, "/uploads/pet-documents/old.jpg"));
        when(fileUtil.upload(file, "pet-documents", "pdf")).thenReturn("/uploads/pet-documents/new.pdf");
        LocalDate issuedDate = LocalDate.of(2026, 7, 31);

        PetDocumentResponse response = service.uploadPetDocument(
                "member-1", "pet-1", "VACCINATION", file, issuedDate);

        verify(petDocumentMapper).update(argThat(row ->
                "VACCINATION".equals(row.get("docType"))
                        && "/uploads/pet-documents/new.pdf".equals(row.get("fileUrl"))
                        && issuedDate.equals(row.get("issuedDate"))));
        assertEquals("2026-07-31", response.getIssuedDate());
    }

    @Test
    void should_registerMedicalConfirmationWithoutReplacingVaccination() throws IOException {
        givenOwner();
        MockMultipartFile file = pdf("medical-confirmation.pdf", "application/pdf");
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "MEDICAL_CONFIRMATION"))
                .thenReturn(null);
        when(fileUtil.upload(file, "pet-documents", "pdf"))
                .thenReturn("/uploads/pet-documents/medical.pdf");

        PetDocumentResponse response = service.uploadPetDocument(
                "member-1", "pet-1", "MEDICAL_CONFIRMATION", file, LocalDate.of(2026, 8, 10));

        assertEquals("MEDICAL_CONFIRMATION", response.getDocType());
        verify(petDocumentMapper).findByPetIdAndTypeForUpdate("pet-1", "MEDICAL_CONFIRMATION");
        verify(petDocumentMapper, never()).findByPetIdAndTypeForUpdate("pet-1", "VACCINATION");
        verify(petDocumentMapper).insert(argThat(row ->
                "MEDICAL_CONFIRMATION".equals(row.get("docType"))));
    }

    @Test
    void should_updateOnlyMedicalConfirmation_when_reuploaded() throws IOException {
        givenOwner();
        MockMultipartFile file = png("medical-new.png", "image/png");
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "MEDICAL_CONFIRMATION"))
                .thenReturn(document(8L, "/uploads/pet-documents/medical-old.pdf"));
        when(fileUtil.upload(file, "pet-documents", "png"))
                .thenReturn("/uploads/pet-documents/medical-new.png");

        service.uploadPetDocument("member-1", "pet-1", "medical_confirmation", file, null);

        verify(petDocumentMapper).update(argThat(row ->
                Long.valueOf(8L).equals(row.get("docId"))
                        && "MEDICAL_CONFIRMATION".equals(row.get("docType"))));
        verify(fileUtil).delete("/uploads/pet-documents/medical-old.pdf");
    }

    @Test
    void should_throwBadRequest_when_documentTypeIsNotUploadable() {
        givenOwner();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadPetDocument(
                        "member-1", "pet-1", "REGISTRATION", jpeg(), null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("문서 유형은 VACCINATION 또는 MEDICAL_CONFIRMATION만 가능합니다.",
                exception.getMessage());
        verifyNoInteractions(petDocumentMapper, fileUtil);
    }

    @Test
    void should_throwForbidden_when_nonOwnerUploads() {
        when(petMapper.findById("pet-1")).thenReturn(pet("owner-1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadPetDocument("member-2", "pet-1", "VACCINATION", jpeg(), null));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verifyNoInteractions(petDocumentMapper, fileUtil);
    }

    @Test
    void should_throwNotFound_when_missingPetUploads() {
        when(petMapper.findById("pet-404")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadPetDocument("member-1", "pet-404", "VACCINATION", jpeg(), null));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verifyNoInteractions(petDocumentMapper, fileUtil);
    }

    @Test
    void should_throwBadRequest_when_fileIsEmpty() {
        givenOwner();
        MockMultipartFile empty = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadPetDocument("member-1", "pet-1", "VACCINATION", empty, null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(petDocumentMapper, fileUtil);
    }

    @Test
    void should_throwBadRequest_when_fileTypeIsNotAllowed() {
        givenOwner();
        MockMultipartFile file = new MockMultipartFile("file", "script.exe", "application/octet-stream", "x".getBytes());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadPetDocument("member-1", "pet-1", "VACCINATION", file, null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(petDocumentMapper, fileUtil);
    }

    @Test
    void should_throwBadRequest_when_contentTypeDoesNotMatchExtension() {
        givenOwner();
        MockMultipartFile file = png("fake.pdf", "image/png");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadPetDocument("member-1", "pet-1", "VACCINATION", file, null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(petDocumentMapper, fileUtil);
    }

    @Test
    void should_acceptPng_when_clientSendsGenericContentType() throws IOException {
        givenOwner();
        MockMultipartFile file = png("medical-confirmation.png", "application/octet-stream");
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "MEDICAL_CONFIRMATION"))
                .thenReturn(null);
        when(fileUtil.upload(file, "pet-documents", "png"))
                .thenReturn("/uploads/pet-documents/medical-confirmation.png");

        PetDocumentResponse response = service.uploadPetDocument(
                "member-1", "pet-1", "MEDICAL_CONFIRMATION", file, LocalDate.of(2026, 8, 10));

        assertEquals("MEDICAL_CONFIRMATION", response.getDocType());
        assertEquals("medical-confirmation.png", response.getDocName());
        verify(petDocumentMapper).insert(anyMap());
    }

    @Test
    void should_storeFilenameOnly_when_originalFilenameContainsPath() throws IOException {
        givenOwner();
        MockMultipartFile file = png("C:\\fakepath\\몽이_접종증명서.png", "image/png");
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "VACCINATION")).thenReturn(null);
        when(fileUtil.upload(file, "pet-documents", "png")).thenReturn("/uploads/pet-documents/new.png");

        PetDocumentResponse response = service.uploadPetDocument(
                "member-1", "pet-1", "VACCINATION", file, null);

        assertEquals("몽이_접종증명서.png", response.getDocName());
        verify(petDocumentMapper).insert(argThat(row ->
                "몽이_접종증명서.png".equals(row.get("docName"))));
    }

    @Test
    void should_throwBadRequest_when_originalFilenameExceedsDatabaseLimit() {
        givenOwner();
        String filename = "a".repeat(97) + ".png";
        MockMultipartFile file = png(filename, "image/png");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadPetDocument("member-1", "pet-1", "VACCINATION", file, null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(petDocumentMapper, fileUtil);
    }

    @Test
    void should_throwInternalServerError_when_fileStorageFails() throws IOException {
        givenOwner();
        MockMultipartFile file = jpeg();
        when(fileUtil.upload(file, "pet-documents", "jpg")).thenThrow(new IOException("disk full"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.uploadPetDocument("member-1", "pet-1", "VACCINATION", file, null));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
        verify(petDocumentMapper, never()).insert(anyMap());
        verify(petDocumentMapper, never()).update(anyMap());
    }

    @Test
    void should_keepExistingDocument_when_databaseUpdateFails() throws IOException {
        givenOwner();
        MockMultipartFile file = jpeg();
        when(petDocumentMapper.findByPetIdAndTypeForUpdate("pet-1", "VACCINATION"))
                .thenReturn(document(7L, "/uploads/pet-documents/old.pdf"));
        when(fileUtil.upload(file, "pet-documents", "jpg")).thenReturn("/uploads/pet-documents/new.jpg");
        doThrow(new RuntimeException("database error")).when(petDocumentMapper).update(anyMap());

        assertThrows(RuntimeException.class,
                () -> service.uploadPetDocument("member-1", "pet-1", "VACCINATION", file, null));

        verify(fileUtil).delete("/uploads/pet-documents/new.jpg");
        verify(fileUtil, never()).delete("/uploads/pet-documents/old.pdf");
    }

    private void givenOwner() {
        when(petMapper.findById("pet-1")).thenReturn(pet("member-1"));
    }

    private static MockMultipartFile jpeg() {
        return new MockMultipartFile("file", "certificate.jpeg", "image/jpeg",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
    }

    private static MockMultipartFile png(String filename, String contentType) {
        return new MockMultipartFile("file", filename, contentType,
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
    }

    private static MockMultipartFile pdf(String filename, String contentType) {
        return new MockMultipartFile("file", filename, contentType,
                "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
    }

    private static Map<String, Object> pet(String ownerId) {
        Map<String, Object> pet = new HashMap<>();
        pet.put("pet_id", "pet-1");
        pet.put("member_id", ownerId);
        return pet;
    }

    private static Map<String, Object> document(long docId, String fileUrl) {
        Map<String, Object> document = new HashMap<>();
        document.put("doc_id", docId);
        document.put("file_url", fileUrl);
        return document;
    }
}
