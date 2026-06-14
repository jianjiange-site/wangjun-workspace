package com.dating.user.service;

import com.dating.user.client.SmsVerificationClient;
import com.dating.user.controller.PhoneLoginRequestDto;
import com.dating.user.controller.PhoneVerificationCodeRequestDto;
import com.dating.user.exception.UserServiceException;
import com.dating.user.manager.PhoneVerificationCodeManager;
import com.dating.user.manager.PhoneVerificationCodeVerifyResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTests {

    @Test
    void generatedPhoneCodeCanLoginOnceAndUsesSmsSdkSlot() {
        CapturingSmsVerificationClient smsClient = new CapturingSmsVerificationClient();
        PhoneVerificationCodeManager codeManager = mock(PhoneVerificationCodeManager.class);
        when(codeManager.createCode("13800138000")).thenReturn("123456");
        when(codeManager.expiresInSeconds()).thenReturn(300L);
        when(codeManager.consumeCode("13800138000", "123456"))
                .thenReturn(PhoneVerificationCodeVerifyResult.MATCHED)
                .thenReturn(PhoneVerificationCodeVerifyResult.INVALID);
        AuthService authService = new AuthService(
                codeManager,
                smsClient
        );

        var code = authService.createPhoneLoginCode(new PhoneVerificationCodeRequestDto("13800138000"));

        assertThat(code.phoneNumber()).isEqualTo("13800138000");
        assertThat(code.verificationCode()).matches("\\d{6}");
        assertThat(code.expiresInSeconds()).isEqualTo(300);
        assertThat(smsClient.phoneNumber).isEqualTo("13800138000");
        assertThat(smsClient.verificationCode).isEqualTo(code.verificationCode());

        var login = authService.phoneLogin(new PhoneLoginRequestDto(
                "13800138000",
                code.verificationCode(),
                "req_phone_login_001"
        ));

        assertThat(login.userId()).isEqualTo("usr_skeleton");
        assertThat(login.status()).isEqualTo("USER_STATUS_PENDING_LIVENESS");
        assertThat(login.nextAction()).isEqualTo("NEXT_ACTION_DO_LIVENESS");
        assertThat(login.newUser()).isTrue();
        assertThatThrownBy(() -> authService.phoneLogin(new PhoneLoginRequestDto(
                "13800138000",
                code.verificationCode(),
                "req_phone_login_002"
        ))).isInstanceOf(UserServiceException.class)
                .hasMessageContaining("Invalid or expired verification code");
    }

    private static final class CapturingSmsVerificationClient implements SmsVerificationClient {

        private String phoneNumber;
        private String verificationCode;

        @Override
        public boolean sendLoginCode(String phoneNumber, String verificationCode) {
            this.phoneNumber = phoneNumber;
            this.verificationCode = verificationCode;
            return true;
        }

        @Override
        public boolean verifyCode(String phoneNumber, String verificationCode) {
            return true;
        }
    }
}
