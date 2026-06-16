package site.jianjiange.postservice.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

/**
 * MinIO 对象存储客户端测试，覆盖对象不存在和基础设施错误的异常映射。
 */
class MinioObjectStorageClientTest {

    /**
     * 验证对象不存在错误会映射为对象未找到异常。
     *
     * @throws Exception MinIO SDK 声明的受检异常
     */
    @Test
    void statObjectMapsNoSuchKeyToObjectNotFound() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(errorResponseException("NoSuchKey"));
        MinioObjectStorageClient client = new MinioObjectStorageClient(minioClient);

        assertThatThrownBy(() -> client.statObject("wangjun-dating", "missing.jpg"))
                .isInstanceOf(ObjectStorageObjectNotFoundException.class);
    }

    /**
     * 验证桶不存在错误会保留为对象存储操作失败，而不是误报图片未上传。
     *
     * @throws Exception MinIO SDK 声明的受检异常
     */
    @Test
    void statObjectMapsNoSuchBucketToStorageFailure() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(errorResponseException("NoSuchBucket"));
        MinioObjectStorageClient client = new MinioObjectStorageClient(minioClient);

        assertThatThrownBy(() -> client.statObject("missing-bucket", "image.jpg"))
                .isInstanceOf(ObjectStorageException.class)
                .isNotInstanceOf(ObjectStorageObjectNotFoundException.class);
    }

    /**
     * 构造 MinIO 错误响应异常。
     *
     * @param code MinIO 错误码
     * @return MinIO 错误响应异常
     */
    private ErrorResponseException errorResponseException(String code) {
        ErrorResponse errorResponse = new ErrorResponse(
                code,
                "error",
                "bucket",
                "object",
                "/bucket/object",
                "request-id",
                "host-id");
        Response response = new Response.Builder()
                .request(new Request.Builder().url("http://localhost/bucket/object").build())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .build();
        return new ErrorResponseException(errorResponse, response, "http trace");
    }
}
