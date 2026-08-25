package com.aewol.domain.explore.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aewol.common.exception.BusinessException;
import com.aewol.common.storage.FileStorage;
import com.aewol.domain.explore.dto.ExplorePageResponse;
import com.aewol.domain.explore.dto.ExplorePostResponse;
import com.aewol.domain.explore.dto.PetPublicProfileResponse;
import com.aewol.domain.explore.mapper.ExploreMapper;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExploreServiceImplTest {

    @Mock ExploreMapper exploreMapper;
    @Mock FileStorage fileStorage;

    @BeforeEach
    void setUp() {
        lenient().when(fileStorage.signedUrl(anyString()))
                .thenAnswer(invocation -> "signed:" + invocation.getArgument(0));
    }

    private ExploreServiceImpl service() {
        return new ExploreServiceImpl(exploreMapper, fileStorage);
    }

    /** 공개 사본이 있는 이미지 행. 사본이 없으면 서비스가 서명 URL로 떨어진다. */
    private static Map<String, Object> imageRow(String diaryId) {
        return Map.of("diaryId", diaryId, "imageUrl", "diary/" + diaryId + ".png",
                "publicImageKey", "public/" + diaryId + ".png");
    }

    private static Map<String, Object> postRow(String diaryId, String createdAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("diaryId", diaryId);
        row.put("petId", "pet-1");
        row.put("petName", "보리");
        row.put("content", "산책");
        row.put("diaryDate", java.sql.Date.valueOf("2026-08-21"));
        row.put("createdAt", Timestamp.valueOf(createdAt));
        return row;
    }

    // 계정 주체가 반려동물인 것이 이 설계의 핵심이다. 응답에 사람이 섞이면 이점이 사라진다.
    @Test
    @DisplayName("탐색 응답에 사람 정보를 담지 않는다")
    void should_notExposeHumanIdentity() {
        when(exploreMapper.findPublicPosts(isNull(), isNull(), anyInt()))
                .thenReturn(List.of(postRow("d-1", "2026-08-21 10:00:00")));
        when(exploreMapper.findImagesByDiaryIds(List.of("d-1"))).thenReturn(List.of(imageRow("d-1")));
        when(fileStorage.publicUrl(anyString())).thenReturn("https://cdn.test/x.png");

        ExplorePageResponse page = service().getExploreFeed(null, 10);

        assertEquals(1, page.getPosts().size());
        // DTO 자체에 작성자 필드가 없다. 컴파일 시점에 막히는 것이 가장 확실한 방어다.
        assertFalse(java.util.Arrays.stream(page.getPosts().get(0).getClass().getDeclaredFields())
                .anyMatch(field -> field.getName().toLowerCase().contains("author")
                        || field.getName().toLowerCase().contains("member")));
    }

    @Test
    @DisplayName("공개 사본이 없는 글은 원본 서명 URL로 피드에 노출한다")
    void should_useSignedUrl_when_publicImageMissing() {
        when(exploreMapper.findPublicPosts(isNull(), isNull(), anyInt())).thenReturn(List.of(
                postRow("d-1", "2026-08-21 10:00:00"),
                postRow("d-2", "2026-08-21 09:00:00")));
        when(exploreMapper.findImagesByDiaryIds(anyList())).thenReturn(List.of(
                Map.of("diaryId", "d-1", "imageUrl", "diary/a.png", "publicImageKey", "public/x.png"),
                Map.of("diaryId", "d-2", "imageUrl", "diary/b.png")));
        when(fileStorage.publicUrl("public/x.png")).thenReturn("https://cdn.test/public/x.png");

        ExplorePageResponse page = service().getExploreFeed(null, 10);

        assertEquals(2, page.getPosts().size());
        assertEquals("d-1", page.getPosts().get(0).getDiaryId());
        assertEquals("signed:diary/b.png", page.getPosts().get(1).getImageUrl());
        assertNull(page.getNextCursor());
    }

    @Test
    @DisplayName("공개 사진이 없어 목록이 비어도 nextCursor는 남긴다")
    void should_keepNextCursor_when_filteredPostsAreEmpty() {
        when(exploreMapper.findPublicPosts(isNull(), isNull(), anyInt())).thenReturn(List.of(
                postRow("d-3", "2026-08-21 12:00:00"),
                postRow("d-2", "2026-08-21 11:00:00"),
                postRow("d-1", "2026-08-21 10:00:00")));
        when(exploreMapper.findImagesByDiaryIds(anyList())).thenReturn(List.of());

        ExplorePageResponse page = service().getExploreFeed(null, 2);

        assertTrue(page.getPosts().isEmpty());
        assertEquals("2026-08-21 11:00:00|d-2", page.getNextCursor());
    }

    @Test
    @DisplayName("공개 사본 URL을 만들 수 없으면 원본 서명 URL을 사용한다")
    void should_useSignedUrl_when_publicUrlUnavailable() {
        when(exploreMapper.findPublicPosts(isNull(), isNull(), anyInt()))
                .thenReturn(List.of(postRow("d-1", "2026-08-21 10:00:00")));
        when(exploreMapper.findImagesByDiaryIds(List.of("d-1"))).thenReturn(List.of(
                Map.of("diaryId", "d-1", "imageUrl", "diary/a.png",
                        "publicImageKey", "public/x.png")));
        when(fileStorage.publicUrl("public/x.png")).thenReturn(null);

        ExplorePageResponse page = service().getExploreFeed(null, 10);

        assertEquals("signed:diary/a.png", page.getPosts().get(0).getImageUrl());
    }

    @Test
    @DisplayName("다음 장이 있으면 마지막 행으로 커서를 만든다")
    void should_returnNextCursor_when_moreRowsExist() {
        when(exploreMapper.findPublicPosts(isNull(), isNull(), anyInt())).thenReturn(List.of(
                postRow("d-3", "2026-08-21 12:00:00"),
                postRow("d-2", "2026-08-21 11:00:00"),
                postRow("d-1", "2026-08-21 10:00:00")));
        when(exploreMapper.findImagesByDiaryIds(anyList()))
                .thenReturn(List.of(imageRow("d-3"), imageRow("d-2"), imageRow("d-1")));
        when(fileStorage.publicUrl(anyString())).thenReturn("https://cdn.test/x.png");

        ExplorePageResponse page = service().getExploreFeed(null, 2);

        assertEquals(2, page.getPosts().size());
        assertEquals("2026-08-21 11:00:00|d-2", page.getNextCursor());
    }

    @Test
    @DisplayName("마지막 장이면 커서를 주지 않는다")
    void should_returnNullCursor_when_lastPage() {
        when(exploreMapper.findPublicPosts(isNull(), isNull(), anyInt()))
                .thenReturn(List.of(postRow("d-1", "2026-08-21 10:00:00")));
        when(exploreMapper.findImagesByDiaryIds(anyList())).thenReturn(List.of());

        assertNull(service().getExploreFeed(null, 10).getNextCursor());
    }

    @Test
    @DisplayName("받은 커서를 정렬 키와 id로 갈라 넘긴다")
    void should_splitCursorIntoSortKeyAndId() {
        when(exploreMapper.findPublicPosts(anyString(), anyString(), anyInt())).thenReturn(List.of());

        service().getExploreFeed("2026-08-21 11:00:00|d-2", 10);

        ArgumentCaptor<String> at = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        verify(exploreMapper).findPublicPosts(at.capture(), id.capture(), anyInt());
        assertEquals("2026-08-21 11:00:00", at.getValue());
        assertEquals("d-2", id.getValue());
    }

    // 커서는 화면이 그대로 돌려보내는 값이라 깨질 수 있다. 400으로 막을 만큼 중요하지 않다.
    @Test
    @DisplayName("형식이 어긋난 커서는 무시하고 첫 장을 준다")
    void should_ignoreMalformedCursor() {
        when(exploreMapper.findPublicPosts(isNull(), isNull(), anyInt())).thenReturn(List.of());

        service().getExploreFeed("쓰레기값", 10);

        verify(exploreMapper).findPublicPosts(isNull(), isNull(), anyInt());
    }

    @Test
    @DisplayName("공개 게시물 상세를 사진과 함께 준다")
    void should_returnPublicPostDetail() {
        when(exploreMapper.findPublicPost("d-1")).thenReturn(postRow("d-1", "2026-08-21 10:00:00"));
        when(exploreMapper.findImagesByDiaryIds(List.of("d-1"))).thenReturn(List.of(
                Map.of("diaryId", "d-1", "imageUrl", "diary/a.png", "publicImageKey", "public/x.png")));
        when(fileStorage.publicUrl("public/x.png")).thenReturn("https://cdn.test/public/x.png");

        ExplorePostResponse post = service().getPost("d-1");

        assertEquals("보리", post.getPetName());
        assertEquals("https://cdn.test/public/x.png", post.getImageUrl());
    }

    // 비공개 글의 id를 알아내도 열리면 안 된다. 조회 조건이 목록과 같아야 한다.
    @Test
    @DisplayName("공개되지 않은 게시물은 상세도 404")
    void should_throwNotFound_when_postIsNotPublic() {
        when(exploreMapper.findPublicPost("secret")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().getPost("secret"));
        assertEquals(404, exception.getStatus().value());
    }

    @Test
    @DisplayName("프로필은 AI 캐릭터를 실사진보다 먼저 쓴다")
    void should_preferCharacterImage() {
        Map<String, Object> row = new HashMap<>();
        row.put("petId", "pet-1");
        row.put("name", "보리");
        row.put("characterImg", "pet/character.png");
        row.put("profileImg", "pet/photo.png");
        row.put("postCount", 3);
        when(exploreMapper.findPublicProfile("pet-1")).thenReturn(row);

        PetPublicProfileResponse profile = service().getPetProfile("pet-1");

        assertEquals("signed:pet/character.png", profile.getProfileImage());
        assertEquals(3, profile.getPostCount());
    }

    @Test
    @DisplayName("없는 반려동물 프로필은 404")
    void should_throwNotFound_when_petMissing() {
        when(exploreMapper.findPublicProfile("nope")).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().getPetProfile("nope"));
        assertEquals(404, exception.getStatus().value());
    }
}
