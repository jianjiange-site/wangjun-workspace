package com.dating.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class QuickLoginRequest {
    @NotBlank private String deviceId;
    @NotBlank private String refreshToken;
}
