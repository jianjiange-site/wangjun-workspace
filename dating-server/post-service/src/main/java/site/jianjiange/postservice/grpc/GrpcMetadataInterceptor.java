package site.jianjiange.postservice.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.springframework.stereotype.Component;

/**
 * 将网关传入的 gRPC metadata 提取到当前调用 Context。
 */
@Component
public class GrpcMetadataInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        Context context = Context.current().withValue(
                GrpcRequestContexts.REQUEST_CONTEXT_KEY,
                GrpcRequestContexts.fromMetadata(headers));
        return Contexts.interceptCall(context, call, headers, next);
    }
}
