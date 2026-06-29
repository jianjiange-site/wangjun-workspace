package site.jianjiange.mobilegateway.grpc.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import site.jianjiange.mobilegateway.client.UserGrpcClient;
import site.jianjiange.mobilegateway.context.AuthContext;
import site.jianjiange.mobilegateway.context.AuthContextHolder;
import site.jianjiange.mobilegateway.converter.RestGrpcConverter;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.grpc.user.GetCurrentUserRequest;
import site.jianjiange.mobilegateway.grpc.user.GetCurrentUserResponse;
import site.jianjiange.mobilegateway.vo.CurrentUserVO;

/**
 * user-service gRPC adapter 转换和 metadata 透传测试。
 */
class UserGrpcAdapterTest {

    private static final Metadata.Key<String> TRACE_ID_METADATA_KEY =
            Metadata.Key.of("trace_id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> USER_ID_METADATA_KEY =
            Metadata.Key.of("user_id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> ACCOUNT_ID_METADATA_KEY =
            Metadata.Key.of("account_id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> DEVICE_ID_METADATA_KEY =
            Metadata.Key.of("device_id", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * 每个测试结束后清理认证上下文。
     */
    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    /**
     * 验证 adapter 从认证上下文构造 proto request、metadata 并转换 VO。
     */
    @Test
    void getCurrentUserBuildsRequestMetadataAndVo() {
        UserGrpcClient client = mock(UserGrpcClient.class);
        UserGrpcAdapter adapter = new UserGrpcAdapter(client, new RestGrpcConverter());
        AuthContextHolder.set(new AuthContext(1001L, 2002L, 3003L, "trace-stage7",
                "jti-1", Instant.now().plusSeconds(60)));
        when(client.getCurrentUser(any(GetCurrentUserRequest.class), any(Metadata.class)))
                .thenReturn(GetCurrentUserResponse.newBuilder()
                        .setUserId(1001L)
                        .setNickname("Alice")
                        .setAvatarUrl("https://cdn.example.com/a.png")
                        .setBio("hello")
                        .build());

        CurrentUserVO result = adapter.getCurrentUser();

        assertThat(result.userId()).isEqualTo(1001L);
        assertThat(result.nickname()).isEqualTo("Alice");
        assertThat(result.avatarUrl()).isEqualTo("https://cdn.example.com/a.png");
        assertThat(result.bio()).isEqualTo("hello");

        ArgumentCaptor<GetCurrentUserRequest> requestCaptor = ArgumentCaptor.forClass(GetCurrentUserRequest.class);
        ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
        verify(client).getCurrentUser(requestCaptor.capture(), metadataCaptor.capture());
        assertThat(requestCaptor.getValue().getUserId()).isEqualTo(1001L);
        assertThat(requestCaptor.getValue().getTraceId()).isEqualTo("trace-stage7");
        assertThat(metadataCaptor.getValue().get(TRACE_ID_METADATA_KEY)).isEqualTo("trace-stage7");
        assertThat(metadataCaptor.getValue().get(USER_ID_METADATA_KEY)).isEqualTo("1001");
        assertThat(metadataCaptor.getValue().get(ACCOUNT_ID_METADATA_KEY)).isEqualTo("2002");
        assertThat(metadataCaptor.getValue().get(DEVICE_ID_METADATA_KEY)).isEqualTo("3003");
    }

    /**
     * 验证缺失认证上下文时返回未登录。
     */
    @Test
    void missingAuthContextReturnsUnauthorized() {
        UserGrpcAdapter adapter = new UserGrpcAdapter(mock(UserGrpcClient.class), new RestGrpcConverter());

        assertThatThrownBy(adapter::getCurrentUser)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(ResultCode.UNAUTHORIZED));
    }

    /**
     * 验证下游响应无法转换时返回 REST/gRPC 转换失败。
     */
    @Test
    void invalidGrpcResponseReturnsConvertFailed() {
        UserGrpcClient client = mock(UserGrpcClient.class);
        UserGrpcAdapter adapter = new UserGrpcAdapter(client, new RestGrpcConverter());
        AuthContextHolder.set(new AuthContext(1001L, 2002L, 3003L, "trace-stage7",
                "jti-1", Instant.now().plusSeconds(60)));
        when(client.getCurrentUser(any(GetCurrentUserRequest.class), any(Metadata.class)))
                .thenReturn(GetCurrentUserResponse.newBuilder().build());

        assertThatThrownBy(adapter::getCurrentUser)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(ResultCode.REST_GRPC_CONVERT_FAILED));
    }
}
