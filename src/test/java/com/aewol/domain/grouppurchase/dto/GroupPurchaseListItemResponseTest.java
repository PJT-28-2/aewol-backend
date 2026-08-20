package com.aewol.domain.grouppurchase.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GroupPurchaseListItemResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("목록 JSON 키는 isParticipating이며 participating으로 줄이지 않는다")
    void should_serializeFieldAsIsParticipating() throws Exception {
        String json = objectMapper.writeValueAsString(
                GroupPurchaseListItemResponse.builder().isParticipating(true).build());
        JsonNode node = objectMapper.readTree(json);

        assertTrue(node.has("isParticipating"));
        assertTrue(node.get("isParticipating").asBoolean());
        assertFalse(node.has("participating"));
    }
}
