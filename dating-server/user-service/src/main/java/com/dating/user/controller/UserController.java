package com.dating.user.controller;

import com.dating.user.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/_skeleton")
    public ApiResponseVo<ServiceStatusVo> skeleton() {
        // TODO: add REST endpoints only if mobile-gateway needs HTTP access.
        return ApiResponseVo.success(new ServiceStatusVo("user-service", "skeleton"));
    }

    @PostMapping("/phone-login-codes")
    public ApiResponseVo<PhoneVerificationCodeVo> createPhoneLoginCode(
            @Valid @RequestBody PhoneVerificationCodeRequestDto request
    ) {
        return ApiResponseVo.success(authService.createPhoneLoginCode(request));
    }

    @PostMapping("/login/phone")
    public ApiResponseVo<LoginResponseVo> phoneLogin(@Valid @RequestBody PhoneLoginRequestDto request) {
        return ApiResponseVo.success(authService.phoneLogin(request));
    }
}
