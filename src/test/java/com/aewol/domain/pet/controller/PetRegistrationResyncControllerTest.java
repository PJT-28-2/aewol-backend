package com.aewol.domain.pet.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aewol.common.exception.GlobalExceptionHandler;
import com.aewol.domain.pet.dto.PetRegistrationResponse;
import com.aewol.domain.pet.service.PetCharacterService;
import com.aewol.domain.pet.service.PetRegistrationService;
import com.aewol.domain.pet.service.PetService;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PetRegistrationResyncControllerTest {

    private final PetService petService = mock(PetService.class);
    private final PetRegistrationService petRegistrationService = mock(PetRegistrationService.class);
    private final PetCharacterService petCharacterService = mock(PetCharacterService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PetController(petService, petRegistrationService, petCharacterService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("member-1", null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void should_resyncRegistration_usingPetAndDocumentPath() throws Exception {
        when(petRegistrationService.resync("member-1", "pet-1", "doc-1"))
                .thenReturn(PetRegistrationResponse.builder()
                        .petId("pet-1")
                        .docId("doc-1")
                        .verified(true)
                        .build());

        mockMvc.perform(post("/api/pets/{petId}/documents/{docId}/resync", "pet-1", "doc-1"))
                .andExpect(status().isOk());

        verify(petRegistrationService).resync("member-1", "pet-1", "doc-1");
    }
}
