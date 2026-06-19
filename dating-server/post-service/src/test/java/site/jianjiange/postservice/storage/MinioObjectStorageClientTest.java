package site.jianjiange.postservice.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
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
     * 验证删除对象会调用 MinIO SDK。
     *
     * @throws Exception MinIO SDK 声明的受检异常
     */
    @Test
    void deleteObjectCallsMinioRemoveObject() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioObjectStorageClient client = new MinioObjectStorageClient(minioClient);

        client.deleteObject("wangjun-dating", "image.jpg");

        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    /**
     * 验证删除时对象已经不存在会视为幂等成功。
     *
     * @throws Exception MinIO SDK 声明的受检异常
     */
    @Test
    void deleteObjectIgnoresNoSuchKey() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        doThrow(errorResponseException("NoSuchKey"))
                .when(minioClient)
                .removeObject(any(RemoveObjectArgs.class));
        MinioObjectStorageClient client = new MinioObjectStorageClient(minioClient);

        client.deleteObject("wangjun-dating", "missing.jpg");
    }

    /**
     * 验证删除时基础设施错误会映射为对象存储异常。
     *
     * @throws Exception MinIO SDK 声明的受检异常
     */
    @Test
    void deleteObjectMapsNoSuchBucketToStorageFailure() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        doThrow(errorResponseException("NoSuchBucket"))
                .when(minioClient)
                .removeObject(any(RemoveObjectArgs.class));
        MinioObjectStorageClient client = new MinioObjectStorageClient(minioClient);

        assertThatThrownBy(() -> client.deleteObject("missing-bucket", "image.jpg"))
                .isInstanceOf(ObjectStorageException.class);
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
