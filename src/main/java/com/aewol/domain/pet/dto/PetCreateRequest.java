package com.aewol.domain.pet.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PetCreateRequest {
    @NotBlank
    private String name;
    @NotBlank
    private String species;
    private String breed;
    private String birthDate;
    private String gender;
    private Double weight;
    private String neutered;
    @Pattern(regexp = "^$|^(\\d{12}|\\d{15})$", message = "동물등록번호는 12자리 또는 15자리 숫자여야 합니다.")
    private String regNumber;
    private String registrationOwnerName;
    private String iconType;
    private String medicalHistory;
    private String profileImg;

    /**
     * 반려동물 인스타그램 핸들. 선택 항목이다.
     *
     * <p>계정 주체가 반려동물이라 이 값도 사람이 아니라 반려동물에 붙는다. 다만 핸들을
     * 붙이면 외부 계정과 연결되므로 "반려동물 계정이 사람 신원을 가려준다"는 이점은
     * 그만큼 줄어든다. 강요하지 않고 비워 둘 수 있게 한다.
     *
     * <p>앞의 {@code @}와 대소문자는 서비스에서 정규화한다. 검증은 그 전 형태도 받도록
     * {@code @}를 허용한다.
     */
    @Size(max = 31, message = "인스타그램 아이디는 30자까지 입력할 수 있습니다.")
    @Pattern(regexp = "^@?[A-Za-z0-9._]{1,30}$|^$",
            message = "인스타그램 아이디는 영문, 숫자, 마침표, 밑줄만 쓸 수 있습니다.")
    private String instagramId;
}
