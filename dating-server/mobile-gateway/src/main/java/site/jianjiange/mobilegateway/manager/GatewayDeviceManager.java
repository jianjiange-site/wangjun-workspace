package site.jianjiange.mobilegateway.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import site.jianjiange.mobilegateway.entity.GatewayAccountEntity;
import site.jianjiange.mobilegateway.entity.GatewayDeviceEntity;
import site.jianjiange.mobilegateway.mapper.GatewayDeviceMapper;
import site.jianjiange.mobilegateway.support.SnowflakeIdGenerator;

/**
 * 网关设备表访问管理器，封装设备身份查询、创建和最近访问时间更新。
 */
@Component
public class GatewayDeviceManager {

    private final GatewayDeviceMapper deviceMapper;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 创建设备管理器。
     *
     * @param deviceMapper 设备表 Mapper
     * @param idGenerator 雪花 ID 生成器
     */
    public GatewayDeviceManager(GatewayDeviceMapper deviceMapper, SnowflakeIdGenerator idGenerator) {
        this.deviceMapper = deviceMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 按设备标识 hash 查询未删除设备。
     *
     * @param deviceKeyHash 设备标识 HMAC hash
     * @return 设备实体，不存在时返回 null
     */
    public GatewayDeviceEntity findByDeviceKeyHash(String deviceKeyHash) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<GatewayDeviceEntity>()
                .eq(GatewayDeviceEntity::getDeviceKeyHash, deviceKeyHash)
                .eq(GatewayDeviceEntity::getDeleted, AuthConstants.DELETED_NO));
    }

    /**
     * 为指定账号创建设备身份。
     *
     * @param account 账号实体
     * @param deviceKeyHash 设备标识 HMAC hash
     * @param hashVersion HMAC hash 版本
     * @param deviceName 设备展示名称
     * @param clientType 客户端类型
     * @param now 当前时间
     * @return 新创建的设备实体
     */
    public GatewayDeviceEntity createForAccount(GatewayAccountEntity account, String deviceKeyHash, int hashVersion,
                                                String deviceName, String clientType, OffsetDateTime now) {
        GatewayDeviceEntity device = new GatewayDeviceEntity();
        device.setDeviceId(idGenerator.nextId());
        device.setAccountId(account.getAccountId());
        device.setUserId(account.getUserId());
        device.setDeviceKeyHash(deviceKeyHash);
        device.setHashVersion(hashVersion);
        device.setDeviceName(StringUtils.hasText(deviceName) ? deviceName.trim() : "");
        device.setClientType(StringUtils.hasText(clientType) ? clientType.trim() : "");
        device.setStatus(AuthConstants.STATUS_NORMAL);
        device.setLastSeenAt(now);
        device.setCreatedAt(now);
        device.setUpdatedAt(now);
        device.setDeleted(AuthConstants.DELETED_NO);
        deviceMapper.insert(device);
        return device;
    }

    /**
     * 更新设备最近访问时间。
     *
     * @param deviceId 设备业务 ID
     * @param now 当前时间
     */
    public void touchLastSeen(long deviceId, OffsetDateTime now) {
        deviceMapper.update(null, new LambdaUpdateWrapper<GatewayDeviceEntity>()
                .eq(GatewayDeviceEntity::getDeviceId, deviceId)
                .eq(GatewayDeviceEntity::getDeleted, AuthConstants.DELETED_NO)
                .set(GatewayDeviceEntity::getLastSeenAt, now)
                .set(GatewayDeviceEntity::getUpdatedAt, now));
    }
}
