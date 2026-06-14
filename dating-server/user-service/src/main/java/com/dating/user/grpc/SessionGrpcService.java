package com.dating.user.grpc;

import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;
import com.dating.user.grpc.v1.RefreshTokenRequest;
import com.dating.user.grpc.v1.RefreshTokenResponse;
import com.dating.user.grpc.v1.SessionServiceGrpc;
import com.dating.user.grpc.v1.ValidateSessionRequest;
import com.dating.user.grpc.v1.ValidateSessionResponse;
import com.dating.user.service.SessionService;

@Component
public class SessionGrpcService extends SessionServiceGrpc.SessionServiceImplBase {

    private final SessionService sessionService;

    public SessionGrpcService(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public void validateSession(ValidateSessionRequest request, StreamObserver<ValidateSessionResponse> responseObserver) {
        complete(responseObserver, sessionService.validateSession(request));
    }

    @Override
    public void refreshToken(RefreshTokenRequest request, StreamObserver<RefreshTokenResponse> responseObserver) {
        complete(responseObserver, sessionService.refreshToken(request));
    }

    private static <T> void complete(StreamObserver<T> responseObserver, T response) {
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
