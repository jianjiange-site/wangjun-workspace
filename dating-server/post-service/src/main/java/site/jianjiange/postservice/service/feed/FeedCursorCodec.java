package site.jianjiange.postservice.service.feed;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.enums.UserGender;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;

/**
 * Feed cursor 编解码器，使用 HMAC-SHA256 防篡改。
 */
@Component
public class FeedCursorCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final byte[] secretBytes;

    /**
     * 创建 Feed cursor 编解码器。
     *
     * @param cursorSecret cursor 签名密钥；生产环境应由 Nacos 或环境变量注入
     */
    public FeedCursorCodec(
            @Value("${dating.feed.cursor-secret:local-development-feed-cursor-secret}") String cursorSecret) {
        this.secretBytes = cursorSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 生成带签名 cursor。
     *
     * @param cursor 明文载荷
     * @return 前端可传回的 cursor
     */
    public String encode(FeedCursor cursor) {
        String payload = cursor.userId()
                + "|"
                + cursor.targetGender().name()
                + "|"
                + toEpochMillis(cursor.expireAt())
                + "|"
                + toEpochMillis(cursor.newBefore())
                + "|"
                + cursor.hotOffset();
        String payloadPart = URL_ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signaturePart = URL_ENCODER.encodeToString(sign(payloadPart));
        return payloadPart + "." + signaturePart;
    }

    /**
     * 解码并校验 cursor。
     *
     * @param cursor 前端传入 cursor
     * @return cursor 明文；空 cursor 返回 Optional.empty()
     */
    public Optional<FeedCursor> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        String[] parts = cursor.split("\\.", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw invalidCursor();
        }
        byte[] expectedSignature = sign(parts[0]);
        byte[] actualSignature;
        try {
            actualSignature = URL_DECODER.decode(parts[1]);
        } catch (IllegalArgumentException ex) {
            throw invalidCursor();
        }
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw invalidCursor();
        }

        String payload;
        try {
            payload = new String(URL_DECODER.decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw invalidCursor();
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 5) {
            throw invalidCursor();
        }
        try {
            return Optional.of(new FeedCursor(
                    Long.valueOf(fields[0]),
                    UserGender.valueOf(fields[1]),
                    fromEpochMillis(Long.parseLong(fields[2])),
                    fromEpochMillis(Long.parseLong(fields[3])),
                    Integer.parseInt(fields[4])));
        } catch (RuntimeException ex) {
            throw invalidCursor();
        }
    }

    private byte[] sign(String payloadPart) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
            return mac.doFinal(payloadPart.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Feed cursor 签名不可用", ex);
        }
    }

    private long toEpochMillis(OffsetDateTime time) {
        return time.toInstant().toEpochMilli();
    }

    private OffsetDateTime fromEpochMillis(long epochMillis) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private BusinessException invalidCursor() {
        return new BusinessException(PostErrorCode.INVALID_ARGUMENT, "Feed cursor 无效");
    }
}
