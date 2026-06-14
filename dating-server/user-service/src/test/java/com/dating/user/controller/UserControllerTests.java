package com.dating.user.controller;

import com.dating.user.client.SmsVerificationClient;
import com.dating.user.manager.PhoneVerificationCodeManager;
import com.dating.user.service.AuthService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserControllerTests {

    @Test
    void phoneCodeEndpointWrapsSuccessCodeMessageAndData() {
        PhoneVerificationCodeManager codeManager = mock(PhoneVerificationCodeManager.class);
        when(codeManager.createCode("13800138000")).thenReturn("123456");
        when(codeManager.expiresInSeconds()).thenReturn(300L);
        UserController controller = new UserController(new AuthService(
                codeManager,
                new NoopSmsClient()
        ));

        var response = controller.createPhoneLoginCode(new PhoneVerificationCodeRequestDto("13800138000"));

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isEqualTo("USER_SUCCESS");
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.data().phoneNumber()).isEqualTo("13800138000");
        assertThat(response.data().verificationCode()).matches("\\d{6}");
    }

    private static final class NoopSmsClient implements SmsVerificationClient {

        @Override
        public boolean sendLoginCode(String phoneNumber, String verificationCode) {
            return true;
        }

        @Override
        public boolean verifyCode(String phoneNumber, String verificationCode) {
            return true;
        }
    }
}
