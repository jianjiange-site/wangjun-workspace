package com.dating.user.exception;

import com.dating.user.common.ApiResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserServiceException.class)
    public ResponseEntity<ApiResult<Void>> handleUserServiceException(UserServiceException e) {
        ErrorCode ec = e.getErrorCode();
        if (ec == ErrorCode.BAD_REQUEST) {
            return ResponseEntity.badRequest().body(ApiResult.error(ec.getCode(), e.getMessage()));
        }
        if (ec == ErrorCode.UNAUTHORIZED) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error(ec.getCode(), e.getMessage()));
        }
        if (ec == ErrorCode.INTERNAL_ERROR) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResult.error(ec.getCode(), e.getMessage()));
        }
        return ResponseEntity.ok(ApiResult.error(ec.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getFieldError() != null ? e.getFieldError().getDefaultMessage() : "参数校验失败";
        return ResponseEntity.badRequest().body(ApiResult.error(400, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(500, "系统异常"));
    }
}
