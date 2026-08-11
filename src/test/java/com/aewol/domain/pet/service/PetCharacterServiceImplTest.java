package com.aewol.domain.pet.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.ChromaKeyRemover;
import com.aewol.common.util.FileUtil;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.pet.dto.PetCharacterResponse;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.external.gemini.GeminiImageClient;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class PetCharacterServiceImplTest {

    @Mock GeminiImageClient geminiImageClient;
    @Mock ChromaKeyRemover chromaKeyRemover;
    @Mock FileUtil fileUtil;
    @Mock PetMapper petMapper;
    @Mock RedisRateLimiter rateLimiter;

    private PetCharacterServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PetCharacterServiceImpl(
                geminiImageClient, chromaKeyRemover, fileUtil, petMapper, rateLimiter);
        ReflectionTestUtils.setField(service, "dailyLimit", 5);
    }

    @Test
    @DisplayName("전신과 프로필 두 장을 만들어 저장하고 경로를 반영한다")
    void should_generateBothImages_and_saveePaths() throws IOException {
        givenOwnedPet();
        when(geminiImageClient.isConfigured()).thenReturn(true);
        when(rateLimiter.incrementWithExpiry(anyString(), anyLong())).thenReturn(1L);
        when(geminiImageClient.generate(any(), anyString(), anyString()))
                .thenReturn("fullbody".getBytes(), "profile".getBytes());
        when(chromaKeyRemover.removeGreenBackground(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fileUtil.uploadBytes(any(), eq("pet-character"), eq("png")))
                .thenReturn("/uploads/pet-character/full.png", "/uploads/pet-character/face.png");
        when(petMapper.updateCharacterImages(any(), any(), any(), any())).thenReturn(1);

        PetCharacterResponse result = service.generate("member-1", "pet-1", photo());

        assertEquals("/uploads/pet-character/face.png", result.getProfileImg());
        assertEquals("/uploads/pet-character/full.png", result.getCharacterImg());
        assertEquals(4, result.getRemainingToday());
        verify(chromaKeyRemover, times(2)).removeGreenBackground(any());
    }

    @Test
    @DisplayName("2단계에는 배경을 빼기 전 원본을 넘긴다")
    void should_passRawFullbody_toProfileStage() throws IOException {
        givenOwnedPet();
        when(geminiImageClient.isConfigured()).thenReturn(true);
        when(rateLimiter.incrementWithExpiry(anyString(), anyLong())).thenReturn(1L);
        byte[] raw = "raw-fullbody".getBytes();
        byte[] keyed = "keyed-fullbody".getBytes();
        when(geminiImageClient.generate(any(), anyString(), anyString()))
                .thenReturn(raw, "profile".getBytes());
        when(chromaKeyRemover.removeGreenBackground(any())).thenReturn(keyed);
        when(fileUtil.uploadBytes(any(), anyString(), anyString())).thenReturn("/uploads/x.png");
        when(petMapper.updateCharacterImages(any(), any(), any(), any())).thenReturn(1);

        service.generate("member-1", "pet-1", photo());

        // 투명 PNG를 입력하면 모델이 투명 영역을 검게 받아들여 배경색이 틀어진다.
        ArgumentCaptor<byte[]> inputCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(geminiImageClient, times(2)).generate(inputCaptor.capture(), anyString(), anyString());
        assertArrayEquals(raw, inputCaptor.getAllValues().get(1),
                "2단계 입력은 크로마키 이전 원본이어야 한다");
    }

    @Test
    @DisplayName("프로필 생성만 실패하면 전신 이미지는 남긴다")
    void should_keepFullbody_when_profileStageFails() throws IOException {
        givenOwnedPet();
        when(geminiImageClient.isConfigured()).thenReturn(true);
        when(rateLimiter.incrementWithExpiry(anyString(), anyLong())).thenReturn(1L);
        when(geminiImageClient.generate(any(), anyString(), anyString()))
                .thenReturn("fullbody".getBytes(), (byte[]) null);
        when(chromaKeyRemover.removeGreenBackground(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fileUtil.uploadBytes(any(), anyString(), anyString()))
                .thenReturn("/uploads/pet-character/full.png");
        when(petMapper.updateCharacterImages(any(), any(), isNull(), anyString())).thenReturn(1);

        PetCharacterResponse result = service.generate("member-1", "pet-1", photo());

        assertNull(result.getProfileImg());
        assertEquals("/uploads/pet-character/full.png", result.getCharacterImg());
    }

    @Test
    @DisplayName("전신 생성이 실패하면 저장하지 않고 예외를 던진다")
    void should_throw_when_fullbodyStageFails() {
        givenOwnedPet();
        when(geminiImageClient.isConfigured()).thenReturn(true);
        when(rateLimiter.incrementWithExpiry(anyString(), anyLong())).thenReturn(1L);
        when(geminiImageClient.generate(any(), anyString(), anyString())).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.generate("member-1", "pet-1", photo()));

        verify(petMapper, never()).updateCharacterImages(any(), any(), any(), any());
    }

    @Test
    @DisplayName("하루 한도를 넘으면 생성 호출 없이 거절한다")
    void should_rejectWithoutCallingModel_when_dailyLimitExceeded() {
        givenOwnedPet();
        when(geminiImageClient.isConfigured()).thenReturn(true);
        when(rateLimiter.incrementWithExpiry(anyString(), anyLong())).thenReturn(6L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.generate("member-1", "pet-1", photo()));

        assertEquals(409, exception.getStatus().value());
        verify(geminiImageClient, never()).generate(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("남의 반려동물에는 캐릭터를 만들 수 없다")
    void should_throwNotFound_when_petIsNotOwned() {
        when(petMapper.findByIdAndMemberId("pet-1", "member-2")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.generate("member-2", "pet-1", photo()));

        assertEquals(404, exception.getStatus().value());
        verify(rateLimiter, never()).incrementWithExpiry(anyString(), anyLong());
    }

    @Test
    @DisplayName("이미지가 아닌 파일은 한도를 소모하지 않고 거절한다")
    void should_rejectNonImage_withoutConsumingQuota() {
        givenOwnedPet();
        MultipartFile pdf = new MockMultipartFile("photo", "a.pdf", "application/pdf", new byte[] {1});

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.generate("member-1", "pet-1", pdf));

        assertEquals(400, exception.getStatus().value());
        verify(rateLimiter, never()).incrementWithExpiry(anyString(), anyLong());
    }

    @Test
    @DisplayName("재생성하면 이전 이미지를 지워 파일이 쌓이지 않게 한다")
    void should_deletePreviousImages_when_regenerated() throws IOException {
        givenPetWithImages("pet-character/old-face.png", "pet-character/old-full.png");
        givenSuccessfulGeneration("pet-character/new-full.png", "pet-character/new-face.png");
        when(petMapper.updateCharacterImages(any(), any(), any(), any())).thenReturn(1);

        service.generate("member-1", "pet-1", photo());

        verify(fileUtil).delete("pet-character/old-face.png");
        verify(fileUtil).delete("pet-character/old-full.png");
        verify(fileUtil, never()).delete("pet-character/new-full.png");
        verify(fileUtil, never()).delete("pet-character/new-face.png");
    }

    @Test
    @DisplayName("DB 갱신에 실패하면 새로 만든 파일을 지우고 이전 이미지는 남긴다")
    void should_cleanUpNewFiles_when_updateFails() throws IOException {
        givenPetWithImages("pet-character/old-face.png", "pet-character/old-full.png");
        givenSuccessfulGeneration("pet-character/new-full.png", "pet-character/new-face.png");
        when(petMapper.updateCharacterImages(any(), any(), any(), any())).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.generate("member-1", "pet-1", photo()));

        verify(fileUtil).delete("pet-character/new-full.png");
        verify(fileUtil).delete("pet-character/new-face.png");
        verify(fileUtil, never()).delete("pet-character/old-face.png");
        verify(fileUtil, never()).delete("pet-character/old-full.png");
    }

    @Test
    @DisplayName("이미지 저장에 실패하면 그때까지 만든 파일을 정리한다")
    void should_cleanUpPartialFiles_when_storeFails() throws IOException {
        givenOwnedPet();
        when(geminiImageClient.isConfigured()).thenReturn(true);
        when(rateLimiter.incrementWithExpiry(anyString(), anyLong())).thenReturn(1L);
        when(geminiImageClient.generate(any(), anyString(), anyString()))
                .thenReturn("fullbody".getBytes(), "profile".getBytes());
        when(chromaKeyRemover.removeGreenBackground(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // 전신은 저장되고 프로필 저장에서 실패하는 상황
        when(fileUtil.uploadBytes(any(), anyString(), anyString()))
                .thenReturn("pet-character/new-full.png")
                .thenThrow(new IOException("disk full"));

        assertThrows(BusinessException.class, () -> service.generate("member-1", "pet-1", photo()));

        verify(fileUtil).delete("pet-character/new-full.png");
        verify(petMapper, never()).updateCharacterImages(any(), any(), any(), any());
    }

    @Test
    @DisplayName("API 키가 없으면 한도를 소모하지 않고 안내한다")
    void should_rejectWithoutConsumingQuota_when_apiKeyMissing() {
        givenOwnedPet();
        when(geminiImageClient.isConfigured()).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.generate("member-1", "pet-1", photo()));

        verify(rateLimiter, never()).incrementWithExpiry(anyString(), anyLong());
    }

    // ── helpers ──────────────────────────────────────────────────

    private void givenPetWithImages(String profileImg, String characterImg) {
        Map<String, Object> pet = new HashMap<>();
        pet.put("pet_id", "pet-1");
        pet.put("member_id", "member-1");
        pet.put("profile_img", profileImg);
        pet.put("character_img", characterImg);
        when(petMapper.findByIdAndMemberId("pet-1", "member-1")).thenReturn(pet);
    }

    private void givenSuccessfulGeneration(String fullbodyPath, String profilePath) throws IOException {
        when(geminiImageClient.isConfigured()).thenReturn(true);
        when(rateLimiter.incrementWithExpiry(anyString(), anyLong())).thenReturn(1L);
        when(geminiImageClient.generate(any(), anyString(), anyString()))
                .thenReturn("fullbody".getBytes(), "profile".getBytes());
        when(chromaKeyRemover.removeGreenBackground(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fileUtil.uploadBytes(any(), anyString(), anyString()))
                .thenReturn(fullbodyPath, profilePath);
    }

    private void givenOwnedPet() {
        Map<String, Object> pet = new HashMap<>();
        pet.put("pet_id", "pet-1");
        pet.put("member_id", "member-1");
        lenient().when(petMapper.findByIdAndMemberId("pet-1", "member-1")).thenReturn(pet);
    }

    private static MultipartFile photo() {
        return new MockMultipartFile("photo", "pet.png", "image/png", new byte[] {1, 2, 3});
    }
}
