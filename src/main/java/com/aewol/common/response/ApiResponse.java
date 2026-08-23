package com.aewol.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final int status;
    private final String message;
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final T result;
    private final String errorCode;

    public static <T> ApiResponse<T> success(T result) {
        return ApiResponse.<T>builder()
                .status(200)
                .message("success")
                .result(result)
                .build();
    }

    public static <T> ApiResponse<T> success(String message, T result) {
        return ApiResponse.<T>builder()
                .status(200)
                .message(message)
                .result(result)
                .build();
    }

    public static ApiResponse<Void> success() {
        return ApiResponse.<Void>builder()
                .status(200)
                .message("success")
                .build();
    }

    public static <T> ApiResponse<T> created(T result) {
        return ApiResponse.<T>builder()
                .status(201)
                .message("created")
                .result(result)
                .build();
    }

    public static <T> ApiResponse<T> created(String message, T result) {
        return ApiResponse.<T>builder()
                .status(201)
                .message(message)
                .result(result)
                .build();
    }

    /**
     * 요청을 접수했지만 처리는 아직 끝나지 않은 비동기 작업용 응답(202). 결과값이 확정되기 전에
     * 반환하므로 페이로드 없이 안내 메시지만 담는다.
     */
    public static ApiResponse<Void> accepted(String message) {
        return ApiResponse.<Void>builder()
                .status(202)
                .message(message)
                .build();
    }

    public static ApiResponse<Void> error(int status, String message) {
        return ApiResponse.<Void>builder()
                .status(status)
                .message(message)
                .build();
    }

    public static ApiResponse<Void> error(int status, String message, String errorCode) {
        return ApiResponse.<Void>builder()
                .status(status)
                .message(message)
                .errorCode(errorCode)
                .build();
    }
}
