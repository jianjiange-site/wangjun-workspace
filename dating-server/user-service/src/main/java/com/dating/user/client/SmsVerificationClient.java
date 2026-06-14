package com.dating.user.client;

public interface SmsVerificationClient {

    // TODO: implement through Sms-Service gRPC or third-party SMS provider SDK.
    boolean sendLoginCode(String phoneNumber, String verificationCode);

    // TODO: keep this hook if verification is delegated to Sms-Service later.
    boolean verifyCode(String phoneNumber, String verificationCode);
}
