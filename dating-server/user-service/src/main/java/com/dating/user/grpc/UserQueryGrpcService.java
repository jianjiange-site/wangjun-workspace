package com.dating.user.grpc;

import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;
import com.dating.user.grpc.v1.BatchGetUsersRequest;
import com.dating.user.grpc.v1.BatchGetUsersResponse;
import com.dating.user.grpc.v1.GetUserProfileRequest;
import com.dating.user.grpc.v1.GetUserProfileResponse;
import com.dating.user.grpc.v1.GetUserRequest;
import com.dating.user.grpc.v1.GetUserResponse;
import com.dating.user.grpc.v1.UserQueryServiceGrpc;
import com.dating.user.service.UserQueryService;

@Component
public class UserQueryGrpcService extends UserQueryServiceGrpc.UserQueryServiceImplBase {

    private final UserQueryService userQueryService;

    public UserQueryGrpcService(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    @Override
    public void getUser(GetUserRequest request, StreamObserver<GetUserResponse> responseObserver) {
        complete(responseObserver, userQueryService.getUser(request));
    }

    @Override
    public void getUserProfile(GetUserProfileRequest request, StreamObserver<GetUserProfileResponse> responseObserver) {
        complete(responseObserver, userQueryService.getUserProfile(request));
    }

    @Override
    public void batchGetUsers(BatchGetUsersRequest request, StreamObserver<BatchGetUsersResponse> responseObserver) {
        complete(responseObserver, userQueryService.batchGetUsers(request));
    }

    private static <T> void complete(StreamObserver<T> responseObserver, T response) {
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
