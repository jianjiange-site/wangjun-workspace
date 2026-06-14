package com.dating.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SendSmsCodeRequest {
    @NotBlank private String phoneCountryCode;
    @NotBlank private String phone;
    @NotBlank private String scene;
}
