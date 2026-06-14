package com.dating.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PhoneLoginRequest {
    @NotBlank private String phoneCountryCode;
    @NotBlank private String phone;
    @NotBlank private String smsCode;
    @NotBlank private String deviceId;
    @NotBlank private String deviceType;
}
