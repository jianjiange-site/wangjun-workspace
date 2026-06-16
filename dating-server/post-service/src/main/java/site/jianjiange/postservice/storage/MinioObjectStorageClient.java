package site.jianjiange.postservice.storage;

import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/**
 * MinIO 对象存储客户端实现，封装对象元数据查询能力。
 */
@Component
public class MinioObjectStorageClient implements ObjectStorageClient {

    private final MinioClient minioClient;

    /**
     * 创建 MinIO 对象存储客户端。
     *
     * @param minioClient MinIO SDK 客户端
     */
    public MinioObjectStorageClient(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * 查询对象元数据。
     *
     * @param bucket 存储桶
     * @param objectKey 对象 Key
     * @return 对象元数据
     */
    @Override
    public ObjectMetadata statObject(String bucket, String objectKey) {
        try {
            StatObjectResponse response = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return new ObjectMetadata(response.contentType(), response.size(), response.etag());
        } catch (ErrorResponseException ex) {
            if (isObjectNotFound(ex)) {
                throw new ObjectStorageObjectNotFoundException("对象不存在：" + objectKey, ex);
            }
            throw new ObjectStorageException("查询对象元数据失败：" + objectKey, ex);
        } catch (IOException
                 | InvalidKeyException
                 | NoSuchAlgorithmException
                 | io.minio.errors.InsufficientDataException
                 | io.minio.errors.InternalException
                 | io.minio.errors.InvalidResponseException
                 | io.minio.errors.ServerException
                 | io.minio.errors.XmlParserException ex) {
            throw new ObjectStorageException("查询对象元数据失败：" + objectKey, ex);
        }
    }

    /**
     * 判断 MinIO 错误是否表示对象不存在。
     *
     * @param ex MinIO 错误响应异常
     * @return 对象不存在时返回 true
     */
    private boolean isObjectNotFound(ErrorResponseException ex) {
        String code = ex.errorResponse() == null ? "" : ex.errorResponse().code();
        return "NoSuchKey".equals(code)
                || "NoSuchObject".equals(code)
                || "NotFound".equals(code);
    }
}
