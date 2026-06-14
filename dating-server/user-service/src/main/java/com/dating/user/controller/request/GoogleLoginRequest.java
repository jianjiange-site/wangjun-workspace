package com.dating.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleLoginRequest {
    @NotBlank private String provider;
    @NotBlank private String providerUserId;
    @NotBlank private String deviceId;
    @NotBlank private String deviceType;
}
