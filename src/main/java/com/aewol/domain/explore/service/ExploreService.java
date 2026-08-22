package com.aewol.domain.explore.service;

import com.aewol.domain.explore.dto.ExplorePageResponse;
import com.aewol.domain.explore.dto.ExplorePostResponse;
import com.aewol.domain.explore.dto.PetPublicProfileResponse;

public interface ExploreService {

    ExplorePageResponse getExploreFeed(String cursor, int size);

    ExplorePageResponse getPetPosts(String petId, String cursor, int size);

    ExplorePostResponse getPost(String diaryId);

    PetPublicProfileResponse getPetProfile(String petId);
}
