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

    public static ApiResponse<Void> error(int status, String message) {
        return ApiResponse.<Void>builder()
                .status(status)
                .message(message)
                .build();
    }
}
