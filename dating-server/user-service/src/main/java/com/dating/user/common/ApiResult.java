package com.dating.user.common;

import lombok.Data;

@Data
public class ApiResult<T> {

    private int code;
    private String message;
    private T data;

    private ApiResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(0, "success", data);
    }

    public static <T> ApiResult<T> success() {
        return new ApiResult<>(0, "success", null);
    }

    public static <T> ApiResult<T> error(int code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
