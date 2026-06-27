package site.jianjiange.mobilegateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 网关 refresh token 会话表实体。
 */
@Getter
@Setter
@TableName("gateway_refresh_token")
public class GatewayRefreshTokenEntity {

    /**
     * 数据库内部主键，不对外暴露。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * refresh token 会话业务 ID。
     */
    private Long refreshTokenId;

    /**
     * 账号业务 ID。
     */
    private Long accountId;

    /**
     * 设备业务 ID。
     */
    private Long deviceId;

    /**
     * 用户业务 ID。
     */
    private Long userId;

    /**
     * refresh token 不透明随机串中的 jti。
     */
    private String tokenJti;

    /**
     * refresh token 随机 secret 的 HMAC hash。
     */
    private String tokenHash;

    /**
     * refresh token 状态。
     */
    private Integer status;

    /**
     * 签发时间。
     */
    private OffsetDateTime issuedAt;

    /**
     * 过期时间。
     */
    private OffsetDateTime expiresAt;

    /**
     * 撤销时间。
     */
    private OffsetDateTime revokedAt;

    /**
     * 创建时间。
     */
    private OffsetDateTime createdAt;

    /**
     * 更新时间。
     */
    private OffsetDateTime updatedAt;

    /**
     * 逻辑删除标记。
     */
    private Integer deleted;

}
