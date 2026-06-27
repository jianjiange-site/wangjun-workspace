package site.jianjiange.mobilegateway.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import site.jianjiange.mobilegateway.entity.GatewayAccountEntity;
import site.jianjiange.mobilegateway.mapper.GatewayAccountMapper;
import site.jianjiange.mobilegateway.support.SnowflakeIdGenerator;

/**
 * 网关账号表访问管理器，封装账号单表查询和状态更新。
 */
@Component
public class GatewayAccountManager {

    private final GatewayAccountMapper accountMapper;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 创建账号管理器。
     *
     * @param accountMapper 账号表 Mapper
     * @param idGenerator 雪花 ID 生成器
     */
    public GatewayAccountManager(GatewayAccountMapper accountMapper, SnowflakeIdGenerator idGenerator) {
        this.accountMapper = accountMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 按账号业务 ID 查询未删除账号。
     *
     * @param accountId 账号业务 ID
     * @return 账号实体，不存在时返回 null
     */
    public GatewayAccountEntity findByAccountId(long accountId) {
        return accountMapper.selectOne(new LambdaQueryWrapper<GatewayAccountEntity>()
                .eq(GatewayAccountEntity::getAccountId, accountId)
                .eq(GatewayAccountEntity::getDeleted, AuthConstants.DELETED_NO));
    }

    /**
     * 按账号类型和账号标识 hash 查询未删除账号。
     *
     * @param accountType 账号类型
     * @param accountKeyHash 账号标识 HMAC hash
     * @return 账号实体，不存在时返回 null
     */
    public GatewayAccountEntity findByTypeAndHash(String accountType, String accountKeyHash) {
        return accountMapper.selectOne(new LambdaQueryWrapper<GatewayAccountEntity>()
                .eq(GatewayAccountEntity::getAccountType, accountType)
                .eq(GatewayAccountEntity::getAccountKeyHash, accountKeyHash)
                .eq(GatewayAccountEntity::getDeleted, AuthConstants.DELETED_NO));
    }

    /**
     * 创建设备登录专用账号，并生成 account_id 和 user_id。
     *
     * @param accountKeyHash 设备标识 HMAC hash
     * @param hashVersion HMAC hash 版本
     * @param now 当前时间
     * @return 新创建的账号实体
     */
    public GatewayAccountEntity createDeviceAccount(String accountKeyHash, int hashVersion, OffsetDateTime now) {
        GatewayAccountEntity account = new GatewayAccountEntity();
        account.setAccountId(idGenerator.nextId());
        account.setUserId(idGenerator.nextId());
        account.setAccountType(AuthConstants.ACCOUNT_TYPE_DEVICE);
        account.setAccountKeyHash(accountKeyHash);
        account.setHashVersion(hashVersion);
        account.setUserRegisterStatus(AuthConstants.REGISTER_STATUS_PENDING);
        account.setStatus(AuthConstants.STATUS_NORMAL);
        account.setLastLoginAt(now);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        account.setDeleted(AuthConstants.DELETED_NO);
        accountMapper.insert(account);
        return account;
    }

    /**
     * 将账号的 user-service 初始化状态标记为完成。
     *
     * @param accountId 账号业务 ID
     * @param now 当前时间
     */
    public void markRegisterDone(long accountId, OffsetDateTime now) {
        accountMapper.update(null, new LambdaUpdateWrapper<GatewayAccountEntity>()
                .eq(GatewayAccountEntity::getAccountId, accountId)
                .eq(GatewayAccountEntity::getUserRegisterStatus, AuthConstants.REGISTER_STATUS_PENDING)
                .eq(GatewayAccountEntity::getDeleted, AuthConstants.DELETED_NO)
                .set(GatewayAccountEntity::getUserRegisterStatus, AuthConstants.REGISTER_STATUS_DONE)
                .set(GatewayAccountEntity::getUpdatedAt, now));
    }

    /**
     * 更新账号最近登录时间。
     *
     * @param accountId 账号业务 ID
     * @param now 当前时间
     */
    public void touchLastLogin(long accountId, OffsetDateTime now) {
        accountMapper.update(null, new LambdaUpdateWrapper<GatewayAccountEntity>()
                .eq(GatewayAccountEntity::getAccountId, accountId)
                .eq(GatewayAccountEntity::getDeleted, AuthConstants.DELETED_NO)
                .set(GatewayAccountEntity::getLastLoginAt, now)
                .set(GatewayAccountEntity::getUpdatedAt, now));
    }
}
