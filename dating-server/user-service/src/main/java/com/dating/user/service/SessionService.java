package com.dating.user.service;

import org.springframework.stereotype.Service;
import com.dating.user.grpc.v1.RefreshTokenRequest;
import com.dating.user.grpc.v1.RefreshTokenResponse;
import com.dating.user.grpc.v1.ValidateSessionRequest;
import com.dating.user.grpc.v1.ValidateSessionResponse;

@Service
public class SessionService {

    public ValidateSessionResponse validateSession(ValidateSessionRequest request) {
        // TODO: verify signed access token and check revocation/session state.
        boolean hasToken = request.getAccessToken() != null && !request.getAccessToken().isBlank();
        return ValidateSessionResponse.newBuilder()
                .setValid(hasToken)
                .setUserId(hasToken ? "usr_skeleton" : "")
                .setSessionId(hasToken ? "ses_skeleton" : "")
                .build();
    }

    public RefreshTokenResponse refreshToken(RefreshTokenRequest request) {
        // TODO: hash refresh token, validate session_id, rotate tokens, and persist revocation.
        return RefreshTokenResponse.newBuilder()
                .setUserId("usr_skeleton")
                .setAccessToken("todo-access-token")
                .setRefreshToken("todo-refresh-token")
                .setExpiresInSeconds(3600)
                .build();
    }
}
