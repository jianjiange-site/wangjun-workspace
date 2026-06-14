package com.dating.user.client;

import org.springframework.stereotype.Component;

@Component
public class NoopSmsVerificationClient implements SmsVerificationClient {

    @Override
    public boolean sendLoginCode(String phoneNumber, String verificationCode) {
        // TODO: replace with SMS SDK integration.
        return true;
    }

    @Override
    public boolean verifyCode(String phoneNumber, String verificationCode) {
        return true;
    }
}
