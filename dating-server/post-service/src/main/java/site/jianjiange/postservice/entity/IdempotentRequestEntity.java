package site.jianjiange.postservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 幂等请求实体，用于记录写操作的客户端请求标识和响应快照。
 */
@Data
@TableName("idempotent_request")
public class IdempotentRequestEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String operationType;
    private String clientRequestId;
    private String requestHash;
    private Long bizNo;
    private String responseSnapshot;
    private OffsetDateTime createdAt;
    private OffsetDateTime expireAt;
}
