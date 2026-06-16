package site.jianjiange.postservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;
import site.jianjiange.postservice.enums.ImageStatus;

/**
 * 帖子图片实体，映射临时图片、绑定图片和清理状态记录。
 */
@Data
@TableName("post_image")
public class PostImageEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long imageNo;
    private Long userId;
    private Long postId;
    private String bucket;
    private String objectKey;
    private String contentType;
    private Long sizeBytes;
    private String etag;
    private Integer width;
    private Integer height;
    private Integer sortOrder;
    private ImageStatus status;
    private OffsetDateTime uploadExpireAt;
    private OffsetDateTime boundAt;
    private Integer retryCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
