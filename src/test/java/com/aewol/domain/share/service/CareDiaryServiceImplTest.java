package com.aewol.domain.share.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.common.util.RedisRateLimiter;
import com.aewol.domain.activity.mapper.ActivityLogMapper;
import com.aewol.domain.pet.mapper.PetMapper;
import com.aewol.domain.share.dto.CareDiaryReportRequest;
import com.aewol.domain.share.dto.CareDiaryReportResponse;
import com.aewol.domain.share.dto.CareDiaryResponse;
import com.aewol.domain.share.dto.CareDiaryUpdateRequest;
import com.aewol.domain.share.dto.CareDiaryVisibilityRequest;
import com.aewol.domain.share.dto.AdminDiaryReportResolutionRequest;
import com.aewol.domain.inquiry.mapper.InquiryMapper;
import com.aewol.domain.share.mapper.CareDiaryMapper;
import com.aewol.domain.share.mapper.ShareMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class CareDiaryServiceImplTest {

    @Mock CareDiaryMapper careDiaryMapper;
    @Mock ShareMapper shareMapper;
    @Mock PetMapper petMapper;
    @Mock ActivityLogMapper activityLogMapper;
    @Mock FileStorage fileStorage;
    @Mock InquiryMapper inquiryMapper;
    @Mock RedisRateLimiter redisRateLimiter;

    @BeforeEach
    void setUp() {
        // signedUrl은 조회 시점에 붙는 임시 주소라, 키를 알아볼 수 있게만 흉내 낸다.
        lenient().when(fileStorage.signedUrl(anyString()))
                .thenAnswer(invocation -> "signed:" + invocation.getArgument(0));
        lenient().when(redisRateLimiter.incrementWithExpiry(anyString(), anyLong())).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("MANAGER 공동육아 구성원은 사진과 글로 일기를 남길 수 있다")
    void should_createDiary_when_acceptedMemberWrites() throws IOException {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(shareMapper.findAcceptedAccess("pet-1", "member-2"))
                .thenReturn(map("access_id", "access-1", "role", "MANAGER"));
        when(fileStorage.store(any(), eq("diary"), anyString())).thenReturn("diary/a.png");
        when(shareMapper.findMainWalletByMemberId("owner-1")).thenReturn(map("wallet_id", "wallet-1"));
        givenInsertAssignsDiaryId("diary-1");
        givenDiaryDetail("diary-1", "pet-1", "member-2", "2026-08-10", "밥 줬어요");

        CareDiaryResponse result = service.create("member-2", "pet-1", "2026-08-10", "밥 줬어요", image());

        assertEquals("diary-1", result.getId());
        ArgumentCaptor<Map<String, Object>> imageCaptor = mapCaptor();
        verify(careDiaryMapper).insertImage(imageCaptor.capture());
        assertEquals("diary/a.png", imageCaptor.getValue().get("imageUrl"));
    }

    @Test
    @DisplayName("VIEWER는 일기를 작성할 수 없다")
    void should_forbidCreateDiary_whenViewerMemberWrites() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(shareMapper.findAcceptedAccess("pet-1", "member-2"))
                .thenReturn(map("access_id", "access-1", "role", "VIEWER"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create("member-2", "pet-1", "2026-08-10", "밥 줬어요", image()));

        assertEquals(403, exception.getStatus().value());
        verify(careDiaryMapper, never()).insert(anyMap());
        verify(fileStorage, never()).store(any(), anyString(), anyString());
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
        when(fileStorage.store(any(), eq("diary"), anyString())).thenReturn("diary/a.png");
        givenInsertAssignsDiaryId("diary-1");
        givenDiaryDetail("diary-1", "pet-1", "owner-1", "2026-08-10", "글");

        service.create("owner-1", "pet-1", "2026-08-10", "글", mislabeled);

        verify(fileStorage).store(any(), eq("diary"), eq("png"));
    }

    @Test
    @DisplayName("이미지 저장 후 DB 반영이 실패하면 고아 파일을 정리한다")
    void should_deleteStoredImage_whenDatabaseInsertFails() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        givenInsertAssignsDiaryId("diary-1");
        when(fileStorage.store(any(), eq("diary"), eq("png"))).thenReturn("diary/a.png");
        doThrow(new IllegalStateException("db failure"))
                .when(careDiaryMapper).insertImage(anyMap());

        assertThrows(IllegalStateException.class,
                () -> service.create("owner-1", "pet-1", "2026-08-10", "글", image()));

        verify(fileStorage).delete("diary/a.png");
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
        assertEquals(List.of("signed:/uploads/diary/a.png", "signed:/uploads/diary/b.png"),
                result.get(0).getImages());
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
        when(careDiaryMapper.findImagesByDiaryIds(List.of("diary-1"))).thenReturn(List.of(
                map("diaryId", "diary-1", "imageUrl", "diary/a.png")));

        CareDiaryUpdateRequest request = new CareDiaryUpdateRequest();
        ReflectionTestUtils.setField(request, "content", "몰래 수정");
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.update("owner-1", "diary-1", request));
        assertEquals(403, exception.getStatus().value());

        service.delete("owner-1", "diary-1");
        verify(careDiaryMapper).softDelete("diary-1");
        verify(fileStorage).delete("diary/a.png");
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
        return new CareDiaryServiceImpl(careDiaryMapper, shareMapper, petMapper, activityLogMapper,
                fileStorage, inquiryMapper, redisRateLimiter, new TestTransactionManager());
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
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

    @Test
    @DisplayName("보낸 버전이 최신이 아니면 일기를 수정하지 않고 409로 알린다")
    void should_rejectUpdate_when_versionIsStale() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "산책"));
        // 그 사이 다른 곳에서 저장돼 version이 올라간 상태를 흉내 낸다.
        when(careDiaryMapper.update("diary-1", "2026-08-10", "덮어쓰기", 3L)).thenReturn(0);

        CareDiaryUpdateRequest request = new CareDiaryUpdateRequest();
        ReflectionTestUtils.setField(request, "content", "덮어쓰기");
        ReflectionTestUtils.setField(request, "version", 3L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.update("owner-1", "diary-1", request));
        assertEquals(409, exception.getStatus().value());
    }

    @Test
    @DisplayName("버전을 보내지 않으면 예전처럼 검사 없이 수정한다")
    void should_updateWithoutVersionCheck_when_versionIsAbsent() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "산책"));
        when(careDiaryMapper.update("diary-1", "2026-08-10", "수정", null)).thenReturn(1);
        when(careDiaryMapper.findImagesByDiaryIds(List.of("diary-1"))).thenReturn(List.of());

        CareDiaryUpdateRequest request = new CareDiaryUpdateRequest();
        ReflectionTestUtils.setField(request, "content", "수정");

        service.update("owner-1", "diary-1", request);

        verify(careDiaryMapper).update("diary-1", "2026-08-10", "수정", null);
    }

    @Test
    @DisplayName("조회 응답에 다음 수정에 쓸 버전을 함께 내려준다")
    void should_exposeVersion_inDetailResponse() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "산책"));
        when(careDiaryMapper.findImagesByDiaryIds(List.of("diary-1"))).thenReturn(List.of());

        CareDiaryResponse response = service.getDetail("owner-1", "diary-1");

        assertEquals(3L, response.getVersion());
    }

    @Test
    @DisplayName("공개로 바꾸면 사진의 공개 사본을 만들어 키를 저장한다")
    void should_createPublicImageCopy_when_publishing() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "산책"),
                        publicDiaryRow("owner-1"));
        when(careDiaryMapper.findImagesByDiaryIds(List.of("diary-1"))).thenReturn(List.of(
                map("diaryId", "diary-1", "imageUrl", "diary/a.png")));
        when(careDiaryMapper.updateVisibility("diary-1", "PUBLIC")).thenReturn(1);
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of(
                map("imageId", "img-1", "imageUrl", "diary/a.png")));
        when(fileStorage.publish("diary/a.png")).thenReturn("public/xyz.png");

        CareDiaryResponse result = service.changeVisibility(
                "owner-1", "diary-1", visibilityRequest("PUBLIC"));

        assertEquals("PUBLIC", result.getVisibility());
        verify(fileStorage).publish("diary/a.png");
        verify(careDiaryMapper).updatePublicImageKey("img-1", "public/xyz.png");
    }

    @Test
    @DisplayName("비공개로 되돌리면 공개 사본을 지우고 키를 비운다")
    void should_removePublicImageCopy_when_unpublishing() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "산책"));
        when(careDiaryMapper.updateVisibility("diary-1", "PRIVATE")).thenReturn(1);
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of(
                map("imageId", "img-1", "imageUrl", "diary/a.png", "publicImageKey", "public/xyz.png")));
        when(careDiaryMapper.findImagesByDiaryIds(List.of("diary-1"))).thenReturn(List.of());

        service.changeVisibility("owner-1", "diary-1", visibilityRequest("PRIVATE"));

        verify(fileStorage).unpublish("public/xyz.png");
        verify(careDiaryMapper).updatePublicImageKey("img-1", null);
        // 원본은 건드리지 않는다. 지우면 일기 자체가 깨진다.
        verify(fileStorage, never()).delete("diary/a.png");
    }

    // 사본 없이 PUBLIC이면 피드에 빈 칸이 생긴다. 전환 자체를 막는다.
    @Test
    @DisplayName("공개 사본 생성이 실패하면 공개로 바꾸지 않는다")
    void should_keepPrivate_when_publishReturnsNull() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "산책"));
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of(
                map("imageId", "img-1", "imageUrl", "diary/a.png")));
        when(fileStorage.publish("diary/a.png")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.changeVisibility("owner-1", "diary-1", visibilityRequest("PUBLIC")));

        assertEquals(409, exception.getStatus().value());
        verify(careDiaryMapper, never()).updateVisibility(anyString(), anyString());
        verify(careDiaryMapper, never()).updatePublicImageKey(anyString(), anyString());
    }

    @Test
    @DisplayName("여러 장 중 뒤 사진 공개가 실패하면 이미 만든 사본을 지운다")
    void should_unpublishCreatedCopy_when_laterImagePublishFails() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "산책"));
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of(
                map("imageId", "img-1", "imageUrl", "diary/a.png"),
                map("imageId", "img-2", "imageUrl", "diary/b.png")));
        when(fileStorage.publish("diary/a.png")).thenReturn("public/a.png");
        when(fileStorage.publish("diary/b.png")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.changeVisibility("owner-1", "diary-1", visibilityRequest("PUBLIC")));

        assertEquals(409, exception.getStatus().value());
        verify(fileStorage).unpublish("public/a.png");
        verify(fileStorage, never()).unpublish("public/b.png");
        verify(careDiaryMapper, never()).updateVisibility(anyString(), anyString());
    }

    @Test
    @DisplayName("공개 사본을 만든 뒤 DB 공개 전환이 실패하면 사본을 지운다")
    void should_unpublishCreatedCopies_when_publishDatabaseUpdateFails() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "산책"));
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of(
                map("imageId", "img-1", "imageUrl", "diary/a.png"),
                map("imageId", "img-2", "imageUrl", "diary/b.png")));
        when(fileStorage.publish("diary/a.png")).thenReturn("public/a.png");
        when(fileStorage.publish("diary/b.png")).thenReturn("public/b.png");
        when(careDiaryMapper.updateVisibility("diary-1", "PUBLIC")).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.changeVisibility("owner-1", "diary-1", visibilityRequest("PUBLIC")));

        assertEquals(404, exception.getStatus().value());
        verify(fileStorage).publish("diary/a.png");
        verify(fileStorage).publish("diary/b.png");
        verify(careDiaryMapper).updateVisibility("diary-1", "PUBLIC");
        verify(fileStorage).unpublish("public/a.png");
        verify(fileStorage).unpublish("public/b.png");
    }

    // 원본만 지우면 CDN 사본은 주소를 아는 사람에게 영구히 보인다.
    @Test
    @DisplayName("일기를 지우면 공개 사본까지 정리한다")
    void should_removePublicCopy_when_diaryDeleted() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "산책"));
        when(careDiaryMapper.findImagesByDiaryIds(List.of("diary-1"))).thenReturn(List.of(
                map("diaryId", "diary-1", "imageUrl", "diary/a.png", "publicImageKey", "public/x.png")));
        when(careDiaryMapper.softDelete("diary-1")).thenReturn(1);

        service.delete("owner-1", "diary-1");

        verify(fileStorage).delete("diary/a.png");
        verify(fileStorage).unpublish("public/x.png");
    }

    // 피드에서 빼는 것만으로는 부족하다. 이미 아는 주소로는 계속 열린다.
    @Test
    @DisplayName("신고로 내려가면 공개 사본도 내린다")
    void should_removePublicCopy_when_hiddenByReport() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findById("diary-1")).thenReturn(publicDiaryRow("author-1"));
        when(careDiaryMapper.insertReport(anyMap())).thenAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("reportId", 77L);
            return 1;
        });
        when(careDiaryMapper.hideByReport("diary-1")).thenReturn(1);
        when(careDiaryMapper.countReportsByDiary("diary-1")).thenReturn(3);
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of(
                map("imageId", "img-1", "imageUrl", "diary/a.png", "publicImageKey", "public/x.png")));

        service.report("reporter-1", "diary-1", reportRequest("PRIVACY"));

        verify(fileStorage).unpublish("public/x.png");
        verify(careDiaryMapper).updatePublicImageKey("img-1", null);
        // 원본은 남긴다. 오탐이면 사본을 다시 만들어야 한다.
        verify(fileStorage, never()).delete("diary/a.png");
    }

    private static CareDiaryReportRequest reportRequest(String reason) {
        CareDiaryReportRequest request = new CareDiaryReportRequest();
        ReflectionTestUtils.setField(request, "reason", reason);
        return request;
    }

    private static Map<String, Object> publicDiaryRow(String authorId) {
        Map<String, Object> row = diaryRow("diary-1", "pet-1", authorId, "2026-08-10", "산책");
        row.put("visibility", "PUBLIC");
        return row;
    }

    // 1건으로 내리면 오탐에도 글이 바로 사라진다. 서로 다른 신고가 3건 모이면 그때 내린다.
    @Test
    @DisplayName("신고가 임계치에 닿으면 노출을 멈추고 문의로 잇는다")
    void should_hideWhenReportThresholdReached_and_createInquiry() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findById("diary-1")).thenReturn(publicDiaryRow("author-1"));
        when(careDiaryMapper.insertReport(anyMap())).thenAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("reportId", 77L);
            return 1;
        });
        when(careDiaryMapper.countReportsByDiary("diary-1")).thenReturn(3);
        when(careDiaryMapper.hideByReport("diary-1")).thenReturn(1);
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of());
        doAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("inquiryId", 55L);
            return null;
        }).when(inquiryMapper).insert(anyMap());

        CareDiaryReportResponse response = service.report("reporter-1", "diary-1", reportRequest("SPAM"));

        verify(careDiaryMapper).hideByReport("diary-1");
        verify(careDiaryMapper).linkReportInquiry("77", "55");
        assertTrue(response.isHidden());
        assertTrue(response.getInquiryNumber().startsWith("AEW-"));
    }

    @Test
    @DisplayName("신고가 임계치 미만이면 접수만 하고 글을 내리지 않는다")
    void should_keepVisible_when_reportCountBelowThreshold() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findById("diary-1")).thenReturn(publicDiaryRow("author-1"));
        when(careDiaryMapper.insertReport(anyMap())).thenAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("reportId", 77L);
            return 1;
        });
        when(careDiaryMapper.countReportsByDiary("diary-1")).thenReturn(2);

        CareDiaryReportResponse response = service.report("reporter-1", "diary-1", reportRequest("SPAM"));

        assertFalse(response.isHidden());
        verify(careDiaryMapper, never()).hideByReport(anyString());
        verify(fileStorage, never()).unpublish(anyString());
    }

    @Test
    @DisplayName("짧은 시간에 신고가 너무 많으면 429로 막는다")
    void should_rejectReport_when_rateLimited() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findById("diary-1")).thenReturn(publicDiaryRow("author-1"));
        when(redisRateLimiter.incrementWithExpiry(anyString(), anyLong())).thenReturn(6L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.report("reporter-1", "diary-1", reportRequest("SPAM")));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
        verify(careDiaryMapper, never()).insertReport(anyMap());
    }

    @Test
    @DisplayName("같은 사람이 다시 신고하면 409로 막는다")
    void should_rejectDuplicateReport() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findById("diary-1")).thenReturn(publicDiaryRow("author-1"));
        when(careDiaryMapper.insertReport(anyMap())).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.report("reporter-1", "diary-1", reportRequest("SPAM")));

        assertEquals(409, exception.getStatus().value());
        verify(careDiaryMapper, never()).hideByReport(anyString());
        verify(redisRateLimiter).rollback(startsWith("rate:diary-report:"));
    }

    // 신고로 내리면 관리자만 되돌릴 수 있어 스스로를 잠그는 셈이 된다.
    @Test
    @DisplayName("자기가 쓴 글은 신고할 수 없다")
    void should_rejectSelfReport() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findById("diary-1")).thenReturn(publicDiaryRow("author-1"));

        assertThrows(BusinessException.class,
                () -> service.report("author-1", "diary-1", reportRequest("SPAM")));
        verify(careDiaryMapper, never()).insertReport(anyMap());
    }

    @Test
    @DisplayName("공개되지 않은 일기는 신고 대상이 아니다")
    void should_rejectReport_when_diaryIsPrivate() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "author-1", "2026-08-10", "산책"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.report("reporter-1", "diary-1", reportRequest("SPAM")));
        assertEquals(404, exception.getStatus().value());
    }

    // 접수번호를 주는 것보다 노출을 멈추는 것이 중요하다.
    @Test
    @DisplayName("문의 생성이 실패해도 신고와 비공개 처리는 남는다")
    void should_keepReport_when_inquiryCreationFails() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findById("diary-1")).thenReturn(publicDiaryRow("author-1"));
        when(careDiaryMapper.insertReport(anyMap())).thenAnswer(invocation -> {
            ((Map<String, Object>) invocation.getArgument(0)).put("reportId", 77L);
            return 1;
        });
        when(careDiaryMapper.countReportsByDiary("diary-1")).thenReturn(3);
        when(careDiaryMapper.hideByReport("diary-1")).thenReturn(1);
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of());
        doThrow(new RuntimeException("DB 장애")).when(inquiryMapper).insert(anyMap());

        CareDiaryReportResponse response = service.report("reporter-1", "diary-1", reportRequest("ABUSE"));

        assertTrue(response.isHidden());
        assertNull(response.getInquiryNumber());
    }

    private static CareDiaryVisibilityRequest visibilityRequest(String visibility) {
        CareDiaryVisibilityRequest request = new CareDiaryVisibilityRequest();
        ReflectionTestUtils.setField(request, "visibility", visibility);
        return request;
    }

    @Test
    @DisplayName("작성자는 자기 일기를 공개로 바꿀 수 있다")
    void should_allowAuthorToPublish() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "member-2", "2026-08-10", "산책"));
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of(
                map("imageId", "img-1", "imageUrl", "diary/a.png")));
        when(fileStorage.publish("diary/a.png")).thenReturn("public/xyz.png");
        when(careDiaryMapper.updateVisibility("diary-1", "PUBLIC")).thenReturn(1);
        when(shareMapper.findAcceptedAccess("pet-1", "member-2")).thenReturn(map("access_id", "access-1"));
        when(careDiaryMapper.findImagesByDiaryIds(List.of("diary-1"))).thenReturn(List.of());

        service.changeVisibility("member-2", "diary-1", visibilityRequest("PUBLIC"));

        verify(careDiaryMapper).updateVisibility("diary-1", "PUBLIC");
    }

    // 대표 보호자가 남이 쓴 글을 마음대로 공개하면 "내가 쓴 글인데 내 통제 밖에서
    // 공개됐다"가 된다. 올리는 것은 쓴 사람만 한다.
    // 사진이 없으면 탐색 그리드와 프로필 어디에도 자리가 없다. 공개는 됐는데 아무 데도
    // 안 보이는 상태가 되므로 애초에 막는다.
    @Test
    @DisplayName("사진이 없는 일기는 공개할 수 없다")
    void should_rejectPublish_when_diaryHasNoImage() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "글만 있는 일기"));
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.changeVisibility("owner-1", "diary-1", visibilityRequest("PUBLIC")));

        // 왜 막혔는지가 그대로 화면에 뜬다. 문구가 바뀌면 알아채야 한다.
        assertTrue(e.getMessage().contains("사진이 있는 일기만"), e.getMessage());
        verify(careDiaryMapper, never()).updateVisibility(anyString(), anyString());
    }

    // 사진이 없어도 내리는 것은 막지 않는다. 이미 공개된 글을 되돌리는 길을 막으면 안 된다.
    @Test
    @DisplayName("사진이 없어도 비공개로는 바꿀 수 있다")
    void should_allowUnpublish_when_diaryHasNoImage() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "글만 있는 일기"));
        when(careDiaryMapper.updateVisibility("diary-1", "PRIVATE")).thenReturn(1);
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of());
        when(careDiaryMapper.findImagesByDiaryIds(List.of("diary-1"))).thenReturn(List.of());

        service.changeVisibility("owner-1", "diary-1", visibilityRequest("PRIVATE"));

        verify(careDiaryMapper).updateVisibility("diary-1", "PRIVATE");
    }

    @Test
    @DisplayName("대표 보호자라도 남이 쓴 일기를 공개할 수는 없다")
    void should_rejectPublish_when_notAuthorEvenIfPetOwner() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "member-2", "2026-08-10", "산책"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.changeVisibility("owner-1", "diary-1", visibilityRequest("PUBLIC")));

        assertEquals(403, exception.getStatus().value());
        verify(careDiaryMapper, never()).updateVisibility(anyString(), anyString());
    }

    @Test
    @DisplayName("대표 보호자는 남이 쓴 일기를 비공개로 내릴 수 있다")
    void should_allowPetOwnerToUnpublishOthersDiary() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "member-2", "2026-08-10", "산책"));
        when(careDiaryMapper.updateVisibility("diary-1", "PRIVATE")).thenReturn(1);
        when(careDiaryMapper.findImagesByDiaryIds(List.of("diary-1"))).thenReturn(List.of());

        service.changeVisibility("owner-1", "diary-1", visibilityRequest("PRIVATE"));

        verify(careDiaryMapper).updateVisibility("diary-1", "PRIVATE");
    }

    @Test
    @DisplayName("작성자도 대표 보호자도 아니면 비공개로 내릴 수 없다")
    void should_rejectUnpublish_when_neitherAuthorNorPetOwner() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        when(careDiaryMapper.findById("diary-1"))
                .thenReturn(diaryRow("diary-1", "pet-1", "member-2", "2026-08-10", "산책"));
        when(shareMapper.findAcceptedAccess("pet-1", "member-3")).thenReturn(map("access_id", "access-2"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.changeVisibility("member-3", "diary-1", visibilityRequest("PRIVATE")));

        assertEquals(403, exception.getStatus().value());
    }

    // 신고로 내려간 글을 작성자가 되살릴 수 있으면 신고가 무력해진다.
    @Test
    @DisplayName("신고로 내려간 일기는 작성자도 다시 공개할 수 없다")
    void should_rejectPublish_when_hiddenByReport() {
        CareDiaryServiceImpl service = service();
        givenPetOwnedBy("pet-1", "owner-1");
        Map<String, Object> row = diaryRow("diary-1", "pet-1", "owner-1", "2026-08-10", "산책");
        row.put("hiddenByReportAt", java.sql.Timestamp.valueOf("2026-08-21 10:00:00"));
        when(careDiaryMapper.findById("diary-1")).thenReturn(row);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.changeVisibility("owner-1", "diary-1", visibilityRequest("PUBLIC")));

        assertEquals(409, exception.getStatus().value());
        verify(careDiaryMapper, never()).updateVisibility(anyString(), anyString());
    }

    @Test
    @DisplayName("관리자가 신고를 반려하면 같은 게시물의 미처리 신고를 끝내고 게시물을 복원한다")
    void should_restoreDiary_when_adminDismissesReport() {
        CareDiaryServiceImpl service = service();
        Map<String, Object> pending = adminReportRow("PENDING", null);
        Map<String, Object> resolved = adminReportRow("RESOLVED", "RESTORE");
        when(careDiaryMapper.findAdminReportById("report-1")).thenReturn(pending, resolved);
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of(map(
                "imageId", "image-1", "imageUrl", "diary/original.png", "publicImageKey", null)));
        when(fileStorage.publish("diary/original.png")).thenReturn("public/restored.png");
        when(careDiaryMapper.restoreByReport("diary-1")).thenReturn(1);
        when(careDiaryMapper.resolvePendingReportsByDiary(
                "diary-1", "RESTORE", "오탐", "admin-1")).thenReturn(2);

        var result = service.resolveAdminReport(
                "admin-1", "report-1", resolutionRequest("RESTORE", " 오탐 "));

        assertEquals("RESOLVED", result.getStatus());
        assertEquals("RESTORE", result.getResolution());
        verify(careDiaryMapper).updatePublicImageKey("image-1", "public/restored.png");
        verify(careDiaryMapper).restoreByReport("diary-1");
        verify(inquiryMapper).answerWaitingLinkedToDiary("diary-1", "오탐");
    }

    @Test
    @DisplayName("관리자가 신고를 승인하면 게시물 숨김을 유지한다")
    void should_keepDiaryHidden_when_adminAcceptsReport() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findAdminReportById("report-1"))
                .thenReturn(adminReportRow("PENDING", null),
                        adminReportRow("RESOLVED", "KEEP_HIDDEN"));
        when(careDiaryMapper.resolvePendingReportsByDiary(
                "diary-1", "KEEP_HIDDEN", null, "admin-1")).thenReturn(1);
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of());

        service.resolveAdminReport(
                "admin-1", "report-1", resolutionRequest("KEEP_HIDDEN", null));

        verify(careDiaryMapper, never()).restoreByReport(anyString());
        verify(fileStorage, never()).publish(anyString());
        verify(inquiryMapper).answerWaitingLinkedToDiary("diary-1", "신고가 처리되었습니다.");
    }

    @Test
    @DisplayName("공개 사진 복원에 실패하면 신고를 처리 완료로 바꾸지 않는다")
    void should_notResolveReport_when_publicImageRestoreFails() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findAdminReportById("report-1"))
                .thenReturn(adminReportRow("PENDING", null));
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of(map(
                "imageId", "image-1", "imageUrl", "diary/original.png", "publicImageKey", null)));
        when(fileStorage.publish("diary/original.png")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolveAdminReport(
                        "admin-1", "report-1", resolutionRequest("RESTORE", null)));

        assertEquals(409, exception.getStatus().value());
        verify(careDiaryMapper, never()).restoreByReport(anyString());
        verify(careDiaryMapper, never()).resolvePendingReportsByDiary(
                anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("복원 중 뒤 사진 사본이 실패하면 이미 만든 사본만 지운다")
    void should_unpublishRestoredCopy_when_laterImageRestoreFails() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findAdminReportById("report-1"))
                .thenReturn(adminReportRow("PENDING", null));
        when(careDiaryMapper.findImagesForPublish("diary-1")).thenReturn(List.of(
                map("imageId", "image-1", "imageUrl", "diary/a.png",
                        "publicImageKey", "public/existing.png"),
                map("imageId", "image-2", "imageUrl", "diary/b.png"),
                map("imageId", "image-3", "imageUrl", "diary/c.png")));
        when(fileStorage.publish("diary/b.png")).thenReturn("public/b.png");
        when(fileStorage.publish("diary/c.png")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolveAdminReport(
                        "admin-1", "report-1", resolutionRequest("RESTORE", null)));

        assertEquals(409, exception.getStatus().value());
        verify(fileStorage).unpublish("public/b.png");
        verify(fileStorage, never()).unpublish("public/existing.png");
        verify(careDiaryMapper, never()).restoreByReport(anyString());
    }

    @Test
    @DisplayName("이미 처리된 신고는 다시 처리하지 않는다")
    void should_rejectResolution_when_reportAlreadyResolved() {
        CareDiaryServiceImpl service = service();
        when(careDiaryMapper.findAdminReportById("report-1"))
                .thenReturn(adminReportRow("RESOLVED", "KEEP_HIDDEN"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.resolveAdminReport(
                        "admin-1", "report-1", resolutionRequest("RESTORE", null)));

        assertEquals(409, exception.getStatus().value());
        verify(careDiaryMapper, never()).resolvePendingReportsByDiary(
                anyString(), anyString(), any(), anyString());
    }

    private static AdminDiaryReportResolutionRequest resolutionRequest(
            String resolution, String adminNote) {
        AdminDiaryReportResolutionRequest request = new AdminDiaryReportResolutionRequest();
        ReflectionTestUtils.setField(request, "resolution", resolution);
        ReflectionTestUtils.setField(request, "adminNote", adminNote);
        return request;
    }

    private static Map<String, Object> adminReportRow(String status, String resolution) {
        return map("reportId", "report-1",
                "diaryId", "diary-1",
                "reason", "SPAM",
                "status", status,
                "resolution", resolution,
                "reporterName", "신고자",
                "reporterEmail", "reporter@example.com",
                "authorName", "작성자",
                "petName", "멍이",
                "content", "신고된 게시물",
                "createdAt", java.sql.Timestamp.valueOf("2026-08-24 10:00:00"));
    }

    private static Map<String, Object> diaryRow(String diaryId, String petId, String authorId,
                                                String diaryDate, String content) {
        return map("diaryId", diaryId,
                "petId", petId,
                "authorMemberId", authorId,
                "authorName", "테스터",
                "diaryDate", java.sql.Date.valueOf(diaryDate),
                "content", content,
                "version", 3L,
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
