package com.dating.user.service;

import org.springframework.stereotype.Service;
import com.dating.user.grpc.v1.BindIdentityResponse;
import com.dating.user.grpc.v1.BindPhoneRequest;
import com.dating.user.grpc.v1.BindThirdPartyRequest;
import com.dating.user.grpc.v1.CompleteProfileRequest;
import com.dating.user.grpc.v1.CompleteProfileResponse;
import com.dating.user.grpc.v1.IdentityType;
import com.dating.user.grpc.v1.LivenessResult;
import com.dating.user.grpc.v1.LoginResponse;
import com.dating.user.grpc.v1.LoginWithPhoneRequest;
import com.dating.user.grpc.v1.LoginWithThirdPartyRequest;
import com.dating.user.grpc.v1.LogoutRequest;
import com.dating.user.grpc.v1.LogoutResponse;
import com.dating.user.grpc.v1.NextAction;
import com.dating.user.grpc.v1.QuickLoginRequest;
import com.dating.user.grpc.v1.SubmitLivenessResultRequest;
import com.dating.user.grpc.v1.SubmitLivenessResultResponse;
import com.dating.user.grpc.v1.UpdateBasicInfoRequest;
import com.dating.user.grpc.v1.UpdateBasicInfoResponse;
import com.dating.user.grpc.v1.UserStatus;

import java.time.Instant;

@Service
public class UserCommandService {

    private static final String SKELETON_USER_ID = "usr_skeleton";
    private static final String SKELETON_IDENTITY_ID = "idn_skeleton";
    private static final String SKELETON_LIVENESS_ID = "liv_skeleton";

    public LoginResponse quickLogin(QuickLoginRequest request) {
        // TODO: create or resolve QUICK identity, then issue real session tokens.
        return loginResponse(SKELETON_USER_ID, UserStatus.USER_STATUS_PENDING_LIVENESS,
                NextAction.NEXT_ACTION_DO_LIVENESS, true);
    }

    public LoginResponse loginWithPhone(LoginWithPhoneRequest request) {
        // TODO: verify SMS code through Sms-Service before resolving PHONE identity.
        return loginResponse(SKELETON_USER_ID, UserStatus.USER_STATUS_PENDING_LIVENESS,
                NextAction.NEXT_ACTION_DO_LIVENESS, true);
    }

    public LoginResponse loginWithThirdParty(LoginWithThirdPartyRequest request) {
        // TODO: trust only provider identities already verified by Gateway/BFF or Auth Adapter.
        return loginResponse(SKELETON_USER_ID, UserStatus.USER_STATUS_PENDING_LIVENESS,
                NextAction.NEXT_ACTION_DO_LIVENESS, true);
    }

    public BindIdentityResponse bindPhone(BindPhoneRequest request) {
        // TODO: verify SMS code and bind PHONE identity to the business user_id.
        return bindIdentityResponse(request.getUserId(), IdentityType.IDENTITY_TYPE_PHONE);
    }

    public BindIdentityResponse bindThirdParty(BindThirdPartyRequest request) {
        // TODO: bind verified provider_user_id to the business user_id.
        return bindIdentityResponse(request.getUserId(), IdentityType.IDENTITY_TYPE_THIRD_PARTY);
    }

    public SubmitLivenessResultResponse submitLivenessResult(SubmitLivenessResultRequest request) {
        // TODO: persist liveness result only after database design is approved.
        boolean passed = request.getResult() == LivenessResult.LIVENESS_RESULT_PASS;
        return SubmitLivenessResultResponse.newBuilder()
                .setUserId(userIdOrSkeleton(request.getUserId()))
                .setLivenessVerificationId(SKELETON_LIVENESS_ID)
                .setStatus(passed ? UserStatus.USER_STATUS_PENDING_PROFILE : UserStatus.USER_STATUS_PENDING_LIVENESS)
                .setNextAction(passed ? NextAction.NEXT_ACTION_COMPLETE_PROFILE : NextAction.NEXT_ACTION_DO_LIVENESS)
                .build();
    }

    public CompleteProfileResponse completeProfile(CompleteProfileRequest request) {
        // TODO: validate REAL_PERSON, birth_date adulthood, avatar media, nickname, gender, and race.
        return CompleteProfileResponse.newBuilder()
                .setUserId(userIdOrSkeleton(request.getUserId()))
                .setStatus(UserStatus.USER_STATUS_ACTIVE)
                .setNextAction(NextAction.NEXT_ACTION_ENTER_APP)
                .build();
    }

    public UpdateBasicInfoResponse updateBasicInfo(UpdateBasicInfoRequest request) {
        // TODO: validate tags, city, bio, occupation, height, weight, education, and MBTI.
        return UpdateBasicInfoResponse.newBuilder()
                .setUserId(userIdOrSkeleton(request.getUserId()))
                .setUpdatedAtEpochMillis(Instant.now().toEpochMilli())
                .build();
    }

    public LogoutResponse logout(LogoutRequest request) {
        // TODO: revoke refresh token/session_id in Redis and persistence store.
        return LogoutResponse.newBuilder()
                .setSuccess(true)
                .build();
    }

    private static LoginResponse loginResponse(
            String userId,
            UserStatus status,
            NextAction nextAction,
            boolean newUser
    ) {
        return LoginResponse.newBuilder()
                .setUserId(userIdOrSkeleton(userId))
                .setStatus(status)
                .setNextAction(nextAction)
                .setAccessToken("todo-access-token")
                .setRefreshToken("todo-refresh-token")
                .setExpiresInSeconds(3600)
                .setIsNewUser(newUser)
                .build();
    }

    private static BindIdentityResponse bindIdentityResponse(String userId, IdentityType identityType) {
        return BindIdentityResponse.newBuilder()
                .setUserId(userIdOrSkeleton(userId))
                .setIdentityId(SKELETON_IDENTITY_ID)
                .setIdentityType(identityType)
                .build();
    }

    private static String userIdOrSkeleton(String userId) {
        return userId == null || userId.isBlank() ? SKELETON_USER_ID : userId;
    }
}
