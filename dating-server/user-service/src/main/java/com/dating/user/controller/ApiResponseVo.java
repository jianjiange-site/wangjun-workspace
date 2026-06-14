package com.dating.user.controller;

public record ApiResponseVo<T>(
        boolean success,
        String code,
        String message,
        T data
) {

    private static final String SUCCESS_CODE = "USER_SUCCESS";
    private static final String SUCCESS_MESSAGE = "success";

    public static <T> ApiResponseVo<T> success(T data) {
        return new ApiResponseVo<>(true, SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static <T> ApiResponseVo<T> failure(String code, String message) {
        return new ApiResponseVo<>(false, code, message, null);
    }
}
