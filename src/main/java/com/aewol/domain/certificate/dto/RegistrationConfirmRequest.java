package com.aewol.domain.certificate.dto;

import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 동물등록증 연동 확정 요청.
 *
 * <p>{@code /certificates/registration/sync} 가 돌려준 후보 중 사용자가 매칭 화면에서
 * 고른 것들이 그대로 올라온다.
 */
@Getter
@Setter
@NoArgsConstructor
public class RegistrationConfirmRequest {

    @NotEmpty(message = "연동할 등록증을 선택해주세요.")
    @Valid
    private List<Candidate> candidates;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Candidate {

        @NotBlank(message = "연동할 반려동물을 선택해주세요.")
        private String petId;

        @NotBlank(message = "동물등록번호가 없습니다.")
        private String regNumber;

        private String name;
        private String breed;
        private String gender;
        private String neutered;
        private String birthDate;
        private String rfidCd;
        private String rfidGubun;
        private String orgNm;
        private String officeTel;
        private String aprGbnNm;
        private String regTm;
        private String aprTm;
    }
}
