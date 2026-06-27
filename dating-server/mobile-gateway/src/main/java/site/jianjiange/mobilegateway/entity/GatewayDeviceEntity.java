package site.jianjiange.mobilegateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 网关设备身份表实体。
 */
@Getter
@Setter
@TableName("gateway_device")
public class GatewayDeviceEntity {

    /**
     * 数据库内部主键，不对外暴露。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 设备业务 ID。
     */
    private Long deviceId;

    /**
     * 设备绑定的账号业务 ID。
     */
    private Long accountId;

    /**
     * 设备绑定的用户业务 ID。
     */
    private Long userId;

    /**
     * 设备原始标识的 HMAC hash。
     */
    private String deviceKeyHash;

    /**
     * HMAC hash 版本号。
     */
    private Integer hashVersion;

    /**
     * 设备展示名称。
     */
    private String deviceName;

    /**
     * 客户端类型。
     */
    private String clientType;

    /**
     * 设备状态。
     */
    private Integer status;

    /**
     * 最近访问时间。
     */
    private OffsetDateTime lastSeenAt;

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
