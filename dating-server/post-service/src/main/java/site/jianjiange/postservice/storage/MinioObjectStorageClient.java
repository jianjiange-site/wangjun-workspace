package site.jianjiange.postservice.storage;

import io.minio.MinioClient;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
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
     * 创建对象上传预签名 URL。
     *
     * @param bucket 存储桶
     * @param objectKey 对象 Key
     * @param contentType 内容类型
     * @param ttl URL 有效期
     * @return 预签名上传 URL
     */
    @Override
    public String createUploadUrl(String bucket, String objectKey, String contentType, Duration ttl) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(Math.toIntExact(ttl.toSeconds()))
                    .extraHeaders(Map.of("Content-Type", contentType))
                    .build());
        } catch (IOException
                 | InvalidKeyException
                 | NoSuchAlgorithmException
                 | io.minio.errors.InsufficientDataException
                 | io.minio.errors.InternalException
                 | io.minio.errors.InvalidResponseException
                 | io.minio.errors.ServerException
                 | io.minio.errors.XmlParserException
                 | ErrorResponseException ex) {
            throw new ObjectStorageException("创建对象上传 URL 失败：" + objectKey, ex);
        }
    }

    /**
     * 删除对象。对象已经不存在时视为清理成功，保证清理任务幂等。
     *
     * @param bucket 存储桶
     * @param objectKey 对象 Key
     */
    @Override
    public void deleteObject(String bucket, String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (ErrorResponseException ex) {
            if (!isObjectNotFound(ex)) {
                throw new ObjectStorageException("删除对象失败：" + objectKey, ex);
            }
        } catch (IOException
                 | InvalidKeyException
                 | NoSuchAlgorithmException
                 | io.minio.errors.InsufficientDataException
                 | io.minio.errors.InternalException
                 | io.minio.errors.InvalidResponseException
                 | io.minio.errors.ServerException
                 | io.minio.errors.XmlParserException ex) {
            throw new ObjectStorageException("删除对象失败：" + objectKey, ex);
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
