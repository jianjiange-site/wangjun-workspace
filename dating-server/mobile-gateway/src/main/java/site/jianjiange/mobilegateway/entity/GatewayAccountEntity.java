package site.jianjiange.mobilegateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 网关认证账号表实体。
 */
@Getter
@Setter
@TableName("gateway_account")
public class GatewayAccountEntity {

    /**
     * 数据库内部主键，不对外暴露。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 账号业务 ID。
     */
    private Long accountId;

    /**
     * 全局用户业务 ID。
     */
    private Long userId;

    /**
     * 账号类型，例如 DEVICE、PHONE、GOOGLE。
     */
    private String accountType;

    /**
     * 账号标识 HMAC hash。
     */
    private String accountKeyHash;

    /**
     * HMAC hash 版本号。
     */
    private Integer hashVersion;

    /**
     * user-service 注册初始化状态。
     */
    private Integer userRegisterStatus;

    /**
     * 账号状态。
     */
    private Integer status;

    /**
     * 最近登录时间。
     */
    private OffsetDateTime lastLoginAt;

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
