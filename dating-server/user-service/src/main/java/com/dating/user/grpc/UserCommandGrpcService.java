package com.dating.user.grpc;

import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;
import com.dating.user.grpc.v1.BindIdentityResponse;
import com.dating.user.grpc.v1.BindPhoneRequest;
import com.dating.user.grpc.v1.BindThirdPartyRequest;
import com.dating.user.grpc.v1.CompleteProfileRequest;
import com.dating.user.grpc.v1.CompleteProfileResponse;
import com.dating.user.grpc.v1.LoginResponse;
import com.dating.user.grpc.v1.LoginWithPhoneRequest;
import com.dating.user.grpc.v1.LoginWithThirdPartyRequest;
import com.dating.user.grpc.v1.LogoutRequest;
import com.dating.user.grpc.v1.LogoutResponse;
import com.dating.user.grpc.v1.QuickLoginRequest;
import com.dating.user.grpc.v1.SubmitLivenessResultRequest;
import com.dating.user.grpc.v1.SubmitLivenessResultResponse;
import com.dating.user.grpc.v1.UpdateBasicInfoRequest;
import com.dating.user.grpc.v1.UpdateBasicInfoResponse;
import com.dating.user.grpc.v1.UserCommandServiceGrpc;
import com.dating.user.service.UserCommandService;

@Component
public class UserCommandGrpcService extends UserCommandServiceGrpc.UserCommandServiceImplBase {

    private final UserCommandService userCommandService;

    public UserCommandGrpcService(UserCommandService userCommandService) {
        this.userCommandService = userCommandService;
    }

    @Override
    public void quickLogin(QuickLoginRequest request, StreamObserver<LoginResponse> responseObserver) {
        complete(responseObserver, userCommandService.quickLogin(request));
    }

    @Override
    public void loginWithPhone(LoginWithPhoneRequest request, StreamObserver<LoginResponse> responseObserver) {
        complete(responseObserver, userCommandService.loginWithPhone(request));
    }

    @Override
    public void loginWithThirdParty(LoginWithThirdPartyRequest request, StreamObserver<LoginResponse> responseObserver) {
        complete(responseObserver, userCommandService.loginWithThirdParty(request));
    }

    @Override
    public void bindPhone(BindPhoneRequest request, StreamObserver<BindIdentityResponse> responseObserver) {
        complete(responseObserver, userCommandService.bindPhone(request));
    }

    @Override
    public void bindThirdParty(BindThirdPartyRequest request, StreamObserver<BindIdentityResponse> responseObserver) {
        complete(responseObserver, userCommandService.bindThirdParty(request));
    }

    @Override
    public void submitLivenessResult(
            SubmitLivenessResultRequest request,
            StreamObserver<SubmitLivenessResultResponse> responseObserver
    ) {
        complete(responseObserver, userCommandService.submitLivenessResult(request));
    }

    @Override
    public void completeProfile(CompleteProfileRequest request, StreamObserver<CompleteProfileResponse> responseObserver) {
        complete(responseObserver, userCommandService.completeProfile(request));
    }

    @Override
    public void updateBasicInfo(UpdateBasicInfoRequest request, StreamObserver<UpdateBasicInfoResponse> responseObserver) {
        complete(responseObserver, userCommandService.updateBasicInfo(request));
    }

    @Override
    public void logout(LogoutRequest request, StreamObserver<LogoutResponse> responseObserver) {
        complete(responseObserver, userCommandService.logout(request));
    }

    private static <T> void complete(StreamObserver<T> responseObserver, T response) {
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
