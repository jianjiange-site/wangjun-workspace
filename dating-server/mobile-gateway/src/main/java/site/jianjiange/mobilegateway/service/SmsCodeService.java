package site.jianjiange.mobilegateway.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import site.jianjiange.mobilegateway.client.SmsProviderClient;
import site.jianjiange.mobilegateway.config.SmsConfig;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.manager.AuthConstants;
import site.jianjiange.mobilegateway.support.HmacHasher;
import site.jianjiange.mobilegateway.support.RedisKeyFactory;

/**
 * 手机验证码服务，负责发送冷却、验证码 hash 存储、校验和失败计数。
 */
@Service
@Slf4j
public class SmsCodeService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern CODE_PATTERN = Pattern.compile("^\\d{6}$");
    private static final int CODE_BOUND = 1_000_000;

    private final SmsConfig smsConfig;
    private final HmacHasher hmacHasher;
    private final RedisKeyFactory redisKeyFactory;
    private final StringRedisTemplate redisTemplate;
    private final SmsProviderClient smsProviderClient;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建手机验证码服务。
     *
     * @param smsConfig 短信验证码配置
     * @param hmacHasher HMAC hash 工具
     * @param redisKeyFactory Redis key 工厂
     * @param redisTemplate Redis 客户端
     * @param smsProviderClient 短信供应商客户端
     */
    public SmsCodeService(SmsConfig smsConfig, HmacHasher hmacHasher, RedisKeyFactory redisKeyFactory,
                          StringRedisTemplate redisTemplate, SmsProviderClient smsProviderClient) {
        this.smsConfig = smsConfig;
        this.hmacHasher = hmacHasher;
        this.redisKeyFactory = redisKeyFactory;
        this.redisTemplate = redisTemplate;
        this.smsProviderClient = smsProviderClient;
    }

    /**
     * 发送手机验证码并写入 Redis 验证码 hash 和冷却 key。
     *
     * @param rawPhone 原始手机号
     */
    public void sendCode(String rawPhone) {
        String phone = normalizePhone(rawPhone);
        String phoneHash = hashPhone(phone);
        String cooldownKey = redisKeyFactory.smsCooldown(phoneHash);
        String codeKey = redisKeyFactory.smsCode(phoneHash);
        String code = generateCode();
        try {
            Boolean cooldownCreated = redisTemplate.opsForValue()
                    .setIfAbsent(cooldownKey, "1", Duration.ofSeconds(smsConfig.getCooldownSeconds()));
            if (!Boolean.TRUE.equals(cooldownCreated)) {
                throw new BusinessException(ResultCode.SMS_CODE_SEND_TOO_FREQUENT);
            }
            redisTemplate.opsForValue().set(codeKey, hashCode(phoneHash, code),
                    Duration.ofSeconds(smsConfig.getCodeTtlSeconds()));
            smsProviderClient.sendCode(phone, code);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            redisTemplate.delete(codeKey);
            redisTemplate.delete(cooldownKey);
            log.warn("Send sms code failed, phoneHash={}", phoneHash, exception);
            throw new BusinessException(ResultCode.SYSTEM_ERROR);
        }
    }

    /**
     * 校验手机验证码，成功后删除验证码和失败计数。
     *
     * @param rawPhone 原始手机号
     * @param rawCode 原始验证码
     * @return 手机号 HMAC hash
     */
    public String verifyAndConsume(String rawPhone, String rawCode) {
        String phone = normalizePhone(rawPhone);
        String code = normalizeCode(rawCode);
        String phoneHash = hashPhone(phone);
        ensureVerifyNotTooFrequent(phoneHash);
        String codeKey = redisKeyFactory.smsCode(phoneHash);
        try {
            String storedHash = redisTemplate.opsForValue().get(codeKey);
            if (!StringUtils.hasText(storedHash)
                    || !MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.UTF_8),
                    hashCode(phoneHash, code).getBytes(StandardCharsets.UTF_8))) {
                recordVerifyFailure(phoneHash);
                throw new BusinessException(ResultCode.SMS_CODE_INVALID);
            }
            redisTemplate.delete(codeKey);
            redisTemplate.delete(redisKeyFactory.loginFail(AuthConstants.ACCOUNT_TYPE_PHONE, phoneHash));
            return phoneHash;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Verify sms code failed closed, phoneHash={}", phoneHash, exception);
            throw new BusinessException(ResultCode.SMS_CODE_INVALID);
        }
    }

    /**
     * 规范化并校验手机号。
     *
     * @param rawPhone 原始手机号
     * @return 规范化后的手机号
     */
    public String normalizePhone(String rawPhone) {
        if (!StringUtils.hasText(rawPhone)) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        String phone = rawPhone.trim().replace(" ", "").replace("-", "");
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        return phone;
    }

    /**
     * 对规范化手机号生成 HMAC hash。
     *
     * @param phone 规范化手机号
     * @return 手机号 HMAC hash
     */
    public String hashPhone(String phone) {
        return hmacHasher.hashPhone(phone);
    }

    /**
     * 检查失败计数是否已经超过阈值。
     *
     * @param phoneHash 手机号 HMAC hash
     */
    private void ensureVerifyNotTooFrequent(String phoneHash) {
        try {
            String failCount = redisTemplate.opsForValue()
                    .get(redisKeyFactory.loginFail(AuthConstants.ACCOUNT_TYPE_PHONE, phoneHash));
            if (StringUtils.hasText(failCount) && Integer.parseInt(failCount) >= smsConfig.getMaxVerifyFailures()) {
                throw new BusinessException(ResultCode.SMS_CODE_VERIFY_TOO_FREQUENT);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Read sms verify failure count failed closed, phoneHash={}", phoneHash, exception);
            throw new BusinessException(ResultCode.SMS_CODE_VERIFY_TOO_FREQUENT);
        }
    }

    /**
     * 记录验证码校验失败次数。
     *
     * @param phoneHash 手机号 HMAC hash
     */
    private void recordVerifyFailure(String phoneHash) {
        String failKey = redisKeyFactory.loginFail(AuthConstants.ACCOUNT_TYPE_PHONE, phoneHash);
        Long count = redisTemplate.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(failKey, Duration.ofSeconds(smsConfig.getFailTtlSeconds()));
        }
    }

    /**
     * 规范化验证码。
     *
     * @param rawCode 原始验证码
     * @return 6 位数字验证码
     */
    private String normalizeCode(String rawCode) {
        if (!StringUtils.hasText(rawCode)) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        String code = rawCode.trim();
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new BusinessException(ResultCode.PARAM_ERROR);
        }
        return code;
    }

    /**
     * 生成 6 位数字验证码。
     *
     * @return 6 位验证码
     */
    private String generateCode() {
        return String.format("%06d", secureRandom.nextInt(CODE_BOUND));
    }

    /**
     * 对验证码生成不可逆 hash。
     *
     * @param phoneHash 手机号 HMAC hash
     * @param code 明文验证码
     * @return 验证码 hash
     */
    private String hashCode(String phoneHash, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((phoneHash + ":" + code).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SMS code hash failed", exception);
        }
    }
}
