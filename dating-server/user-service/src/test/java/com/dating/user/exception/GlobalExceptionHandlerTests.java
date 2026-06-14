package com.dating.user.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTests {

    @Test
    void userServiceExceptionReturnsFailureEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleUserServiceException(new UserServiceException(
                ErrorCode.USER_INVALID_ARGUMENT,
                "Invalid or expired verification code"
        ));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo("USER_INVALID_ARGUMENT");
        assertThat(response.getBody().message()).isEqualTo("Invalid or expired verification code");
        assertThat(response.getBody().data()).isNull();
    }
}
