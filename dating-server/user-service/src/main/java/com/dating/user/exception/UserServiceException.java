package com.dating.user.exception;

public class UserServiceException extends RuntimeException {

    private final ErrorCode errorCode;

    public UserServiceException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public UserServiceException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() { return errorCode; }
}
