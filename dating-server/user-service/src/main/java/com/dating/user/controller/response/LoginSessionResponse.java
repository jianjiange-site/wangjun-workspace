package com.dating.user.controller.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginSessionResponse {
    private String loginSessionToken;
    private String nextStep;
}
