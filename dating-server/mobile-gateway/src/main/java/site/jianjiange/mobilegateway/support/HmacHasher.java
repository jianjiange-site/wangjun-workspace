package site.jianjiange.mobilegateway.support;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import site.jianjiange.mobilegateway.config.HashConfig;

/**
 * 按用途隔离 secret 的 HMAC-SHA256 hash 工具。
 */
@Component
public class HmacHasher {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final int version;
    private final HashConfig config;

    /**
     * 创建按用途隔离 secret 的 HMAC 工具。
     *
     * @param config HMAC 配置
     */
    public HmacHasher(HashConfig config) {
        this.version = config.getVersion();
        this.config = config;
    }

    /**
     * 对手机号生成 HMAC hash。
     *
     * @param phone 手机号明文
     * @return 带版本号的 HMAC hash
     */
    public String hashPhone(String phone) {
        return hash(phone, config.getPhoneSecret());
    }

    /**
     * 对 Google subject 生成 HMAC hash。
     *
     * @param subject Google subject
     * @return 带版本号的 HMAC hash
     */
    public String hashGoogleSubject(String subject) {
        return hash(subject, config.getGoogleSubjectSecret());
    }

    /**
     * 对设备原始标识生成 HMAC hash。
     *
     * @param device 设备原始标识
     * @return 带版本号的 HMAC hash
     */
    public String hashDevice(String device) {
        return hash(device, config.getDeviceSecret());
    }

    /**
     * 对 refresh token 随机 secret 生成 HMAC hash。
     *
     * @param refreshTokenSecret refresh token 随机 secret
     * @return 带版本号的 HMAC hash
     */
    public String hashRefreshToken(String refreshTokenSecret) {
        return hash(refreshTokenSecret, config.getRefreshTokenSecret());
    }

    /**
     * 使用指定 secret 执行 HMAC-SHA256。
     *
     * @param value 待 hash 明文
     * @param secret HMAC secret
     * @return 带版本号的 HMAC hash
     */
    private String hash(String value, String secret) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("HMAC value must not be blank");
        }
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("HMAC secret must not be blank");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v" + version + ":" + toHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC hash failed", exception);
        }
    }

    /**
     * 将字节数组转换为小写十六进制字符串。
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
