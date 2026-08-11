package com.aewol.domain.share.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.util.FileUtil;
import com.aewol.domain.activity.mapper.ActivityLogMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.share.dto.CareDiaryResponse;
import com.aewol.domain.share.dto.CareDiaryUpdateRequest;
import com.aewol.domain.share.mapper.CareDiaryMapper;
import com.aewol.domain.share.mapper.ShareMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
class CareDiaryServiceImplTest {

    @Mock CareDiaryMapper careDiaryMapper;
    @Mock ShareMapper shareMapper;
    @Mock PetMapper petMapper;
    @Mock ActivityLogMapper activityLogMapper;
    @Mock FileUtil fileUtil;

    @Test
    @DisplayName("공동육아 구성원도 사진과 글로 일기를 남길 수 있다")
    void should_createDiary_when_acceptedMemberWrites() throws IOException {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(shareMapper.findAcceptedAccess("pet-1", "member-2")).thenReturn(map("access_id", "access-1"));
        when(fileUtil.upload(any(MultipartFile.class), eq("diary"), anyString()))
                .thenReturn("/uploads/diary/a.png");
        when(shareMapper.findMainWalletByMemberId("owner-1")).thenReturn(map("wallet_id", "wallet-1"));
        givenInsertAssignsDiaryId("diary-1");
        givenDiaryDetail("diary-1", "pet-1", "member-2", "2026-08-10", "밥 줬어요");

        CareDiaryResponse result = service.create("member-2", "pet-1", "2026-08-10", "밥 줬어요", image());

        assertEquals("diary-1", result.getId());
        ArgumentCaptor<Map<String, Object>> imageCaptor = mapCaptor();
        verify(careDiaryMapper).insertImage(imageCaptor.capture());
        assertEquals("/uploads/diary/a.png", imageCaptor.getValue().get("imageUrl"));
    }

    @Test
    @DisplayName("이미지가 아닌 파일은 확장자를 속여도 저장하지 않는다")
    void should_rejectNonImage_evenWhenExtensionLooksLikeImage() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        // 업로드 경로로 공개되므로 svg/html이 통과하면 스크립트가 실행될 수 있다.
        MultipartFile svg = new MockMultipartFile("image", "cute.png", "image/png",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>".getBytes());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create("owner-1", "pet-1", "2026-08-10", "글", svg));

        assertEquals(400, exception.getStatus().value());
        verify(careDiaryMapper, never()).insertImage(anyMap());
    }

    @Test
    @DisplayName("허용하지 않는 MIME 타입은 거절한다")
    void should_rejectDisallowedContentType() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        MultipartFile svg = new MockMultipartFile("image", "a.svg", "image/svg+xml", pngBytes());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create("owner-1", "pet-1", "2026-08-10", "글", svg));

        assertEquals(400, exception.getStatus().value());
        verify(careDiaryMapper, never()).insertImage(anyMap());
    }

    @Test
    @DisplayName("확장자는 파일명이 아니라 실제 내용에서 정한다")
    void should_deriveExtensionFromContent_notFilename() throws IOException {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        // 파일명은 .jpg지만 내용은 PNG다. 저장 확장자는 png여야 한다.
        MultipartFile mislabeled = new MockMultipartFile("image", "photo.jpg", "image/png", pngBytes());
        when(fileUtil.upload(any(MultipartFile.class), eq("diary"), anyString()))
                .thenReturn("/uploads/diary/a.png");
        givenInsertAssignsDiaryId("diary-1");
        givenDiaryDetail("diary-1", "pet-1", "owner-1", "2026-08-10", "글");

        service.create("owner-1", "pet-1", "2026-08-10", "글", mislabeled);

        verify(fileUtil).upload(any(MultipartFile.class), eq("diary"), eq("png"));
    }

    @Test
    @DisplayName("사진과 내용이 모두 없으면 일기를 저장하지 않는다")
    void should_reject_when_bothContentAndImageAreEmpty() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create("owner-1", "pet-1", "2026-08-10", "   ", null));

        assertEquals(400, exception.getStatus().value());
        verify(careDiaryMapper, never()).insert(anyMap());
    }

    @Test
    @DisplayName("아직 오지 않은 날짜에는 일기를 남길 수 없다")
    void should_reject_when_diaryDateIsInFuture() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        String tomorrow = LocalDate.now().plusDays(1).toString();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create("owner-1", "pet-1", tomorrow, "미래 기록", null));

        assertEquals(400, exception.getStatus().value());
        verify(careDiaryMapper, never()).insert(anyMap());
    }

    @Test
    @DisplayName("소유자도 참여자도 아니면 일기를 조회할 수 없다")
    void should_throwForbidden_when_memberHasNoAccessToPet() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(shareMapper.findAcceptedAccess("pet-1", "stranger")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.getMonthly("stranger", "pet-1", "2026-08"));

        assertEquals(403, exception.getStatus().value());
    }

    @Test
    @DisplayName("목록 조회는 일기별로 이미지를 다시 조회하지 않고 한 번에 묶어 온다")
    void should_loadImagesInSingleQuery_when_listingMonthlyDiaries() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findByPetIdAndMonth("pet-1", "2026-08")).thenReturn(List.of(
                diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "산책"),
                diaryRow("diary-2", "pet-1", "member-2", "2026-08-09", "목욕")));
        when(careDiaryMapper.findImagesByDiaryIds(List.of("diary-1", "diary-2"))).thenReturn(List.of(
                map("diaryId", "diary-1", "imageUrl", "/uploads/diary/a.png"),
                map("diaryId", "diary-1", "imageUrl", "/uploads/diary/b.png")));

        List<CareDiaryResponse> result = service.getMonthly("owner-1", "pet-1", "2026-08");

        assertEquals(2, result.size());
        assertEquals(List.of("/uploads/diary/a.png", "/uploads/diary/b.png"), result.get(0).getImages());
        assertTrue(result.get(1).getImages().isEmpty());
        verify(careDiaryMapper, times(1)).findImagesByDiaryIds(anyList());
    }

    @Test
    @DisplayName("남이 쓴 일기는 수정할 수 없지만 대표 보호자는 삭제할 수 있다")
    void should_allowOwnerToDeleteButNotEditOthersDiary() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "member-2", "2026-08-10", "밥"));
        when(careDiaryMapper.softDelete("diary-1")).thenReturn(1);

        CareDiaryUpdateRequest request = new CareDiaryUpdateRequest();
        ReflectionTestUtils.setField(request, "content", "몰래 수정");
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.update("owner-1", "diary-1", request));
        assertEquals(403, exception.getStatus().value());

        service.delete("owner-1", "diary-1");
        verify(careDiaryMapper).softDelete("diary-1");
    }

    @Test
    @DisplayName("작성자도 대표 보호자도 아니면 일기를 삭제할 수 없다")
    void should_throwForbidden_when_memberDeletesOthersDiary() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(shareMapper.findAcceptedAccess("pet-1", "member-2")).thenReturn(map("access_id", "access-1"));
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "산책"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.delete("member-2", "diary-1"));

        assertEquals(403, exception.getStatus().value());
        verify(careDiaryMapper, never()).softDelete(anyString());
    }

    @Test
    @DisplayName("이미 삭제된 일기는 찾을 수 없다고 응답한다")
    void should_throwNotFound_when_diaryAlreadySoftDeleted() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findById("diary-1")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.delete("owner-1", "diary-1"));

        assertEquals(404, exception.getStatus().value());
    }

    @Test
    @DisplayName("일기를 남기면 공동육아 활동 내역에도 기록한다")
    void should_writeActivityLog_when_diaryCreated() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(shareMapper.findMainWalletByMemberId("owner-1")).thenReturn(map("wallet_id", "wallet-1"));
        givenInsertAssignsDiaryId("diary-1");
        givenDiaryDetail("diary-1", "pet-1", "owner-1", "2026-08-10", "산책");

        service.create("owner-1", "pet-1", "2026-08-10", "산책", null);

        ArgumentCaptor<Map<String, Object>> logCaptor = mapCaptor();
        verify(activityLogMapper).insert(logCaptor.capture());
        Map<String, Object> log = logCaptor.getValue();
        assertEquals("DIARY", log.get("targetType"));
        assertEquals("diary-1", log.get("targetId"));
        assertEquals("pet-1", log.get("petId"));
    }

    @Test
    @DisplayName("활동 로그 적재가 실패해도 일기 작성은 성공한다")
    void should_keepDiary_when_activityLogInsertFails() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(shareMapper.findMainWalletByMemberId("owner-1")).thenReturn(map("wallet_id", "wallet-1"));
        doThrow(new RuntimeException("log down")).when(activityLogMapper).insert(anyMap());
        givenInsertAssignsDiaryId("diary-1");
        givenDiaryDetail("diary-1", "pet-1", "owner-1", "2026-08-10", "산책");

        CareDiaryResponse result = service.create("owner-1", "pet-1", "2026-08-10", "산책", null);

        assertEquals("diary-1", result.getId());
    }

    @Test
    @DisplayName("조회할 월을 주지 않으면 이번 달을 사용한다")
    void should_useCurrentMonth_when_yearMonthOmitted() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findByPetIdAndMonth(eq("pet-1"), anyString())).thenReturn(List.of());

        service.getMonthly("owner-1", "pet-1", null);

        verify(careDiaryMapper).findByPetIdAndMonth("pet-1",
                java.time.YearMonth.now().toString());
    }

    // ── helpers ──────────────────────────────────────────────────

    private CareDiaryServiceImpl service() {
        return new CareDiaryServiceImpl(careDiaryMapper, shareMapper, petMapper, activityLogMapper, fileUtil);
    }

    private void givenPetOwnedBy(String petId, String ownerId) {
        when(petMapper.findById(petId)).thenReturn(map("pet_id", petId, "member_id", ownerId));
    }

    /** insert()는 useGeneratedKeys로 파라미터 맵에 diaryId를 채워 넣는다. */
    private void givenInsertAssignsDiaryId(String diaryId) {
        doAnswer(invocation -> {
            Map<String, Object> param = invocation.getArgument(0);
            param.put("diaryId", diaryId);
            return null;
        }).when(careDiaryMapper).insert(anyMap());
    }

    private void givenDiaryDetail(String diaryId, String petId, String authorId,
                                  String diaryDate, String content) {
        when(careDiaryMapper.findById(diaryId))
                .thenReturn(diaryRow(diaryId, petId, authorId, diaryDate, content));
    }

    private static Map<String, Object> diaryRow(String diaryId, String petId, String authorId,
                                                String diaryDate, String content) {
        return map("diaryId", diaryId,
                "petId", petId,
                "authorMemberId", authorId,
                "authorName", "테스터",
                "diaryDate", java.sql.Date.valueOf(diaryDate),
                "content", content,
                "createdAt", java.sql.Timestamp.valueOf(diaryDate + " 09:00:00"));
    }

    private static MultipartFile image() {
        return new MockMultipartFile("image", "pet.png", "image/png", pngBytes());
    }

    /** PNG 시그니처로 시작하는 최소 바이트. 저장 전 실제 이미지인지 확인하므로 필요하다. */
    private static byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
