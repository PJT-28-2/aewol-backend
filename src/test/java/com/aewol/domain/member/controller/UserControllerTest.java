package com.aewol.domain.member.controller;

import com.aewol.common.response.ApiResponse;
import com.aewol.domain.member.dto.MemberWithdrawRequest;
import com.aewol.domain.member.service.MemberService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemberService memberService = mock(MemberService.class);
    private final UserController controller = new UserController(memberService);

    @Test
    void withdrawUsesPrincipalAndReturnsConfirmedContract() throws Exception {
        MemberWithdrawRequest request = new MemberWithdrawRequest();
        ReflectionTestUtils.setField(request, "currentPassword", "current-password");

        ResponseEntity<ApiResponse<Void>> entity = controller.withdraw("member-1", request);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(entity.getBody()));

        assertEquals(200, entity.getStatusCodeValue());
        assertEquals(200, json.get("status").asInt());
        assertEquals("회원탈퇴가 완료되었습니다.", json.get("message").asText());
        assertTrue(json.has("result"));
        assertTrue(json.get("result").isNull());
        verify(memberService).withdraw("member-1", request);
    }
}
