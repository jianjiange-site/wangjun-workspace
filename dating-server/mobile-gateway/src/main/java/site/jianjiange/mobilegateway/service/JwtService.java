package site.jianjiange.mobilegateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import lombok.Getter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import site.jianjiange.mobilegateway.config.JwtConfig;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;

/**
 * token 签发服务，access token 使用 RS256 JWT，refresh token 使用不透明随机串。
 */
@Service
public class JwtService {

    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String JWT_ALGORITHM = "RS256";
    private static final String JWT_HEADER_TYPE = "JWT";
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final int REFRESH_TOKEN_RANDOM_BYTES = 32;
    private static final String REFRESH_TOKEN_PREFIX = "rt_";

    private final JwtConfig config;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private PrivateKey privateKey;
    @Getter
    private PublicKey publicKey;

    /**
     * 创建 JWT 服务。
     *
     * @param config JWT 配置
     * @param objectMapper JSON 序列化器
     */
    public JwtService(JwtConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * 从配置加载 RS256 公私钥。
     */
    @PostConstruct
    void loadKeys() {
        try {
            this.privateKey = parsePrivateKey(config.getPrivateKey());
            this.publicKey = parsePublicKey(config.getPublicKey());
        } catch (Exception exception) {
            throw new IllegalStateException(ResultCode.TOKEN_KEY_UNAVAILABLE.getMessage(), exception);
        }
    }

    /**
     * 为指定登录身份签发 JWT access token 和不透明 refresh token。
     *
     * @param accountId 账号业务 ID
     * @param userId 用户业务 ID
     * @param deviceId 设备业务 ID
     * @return 签发后的 token 信息
     */
    public IssuedTokens issue(long accountId, long userId, long deviceId) {
        if (privateKey == null || publicKey == null) {
            throw new BusinessException(ResultCode.TOKEN_KEY_UNAVAILABLE);
        }
        OffsetDateTime issuedAt = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime accessExpiresAt = issuedAt.plusSeconds(config.getAccessTokenTtlSeconds());
        OffsetDateTime refreshExpiresAt = issuedAt.plusSeconds(config.getRefreshTokenTtlSeconds());
        String accessJti = UUID.randomUUID().toString();
        String refreshJti = UUID.randomUUID().toString();
        String accessToken = signToken("access", accessJti, accountId, userId, deviceId, issuedAt, accessExpiresAt);
        String refreshTokenSecret = generateRefreshTokenSecret();
        String refreshToken = REFRESH_TOKEN_PREFIX + refreshJti + "." + refreshTokenSecret;
        return new IssuedTokens(accessToken, refreshToken, refreshTokenSecret, accessJti, refreshJti, issuedAt,
                accessExpiresAt, refreshExpiresAt, config.getAccessTokenTtlSeconds());
    }

    /**
     * 构造 JWT header 和 claim，并使用 RSA 私钥生成 RS256 签名。
     *
     * @param tokenType token 类型，区分 access 和 refresh
     * @param jti token 唯一标识
     * @param accountId 账号业务 ID
     * @param userId 用户业务 ID
     * @param deviceId 设备业务 ID
     * @param issuedAt 签发时间
     * @param expiresAt 过期时间
     * @return 完整 JWT 字符串
     */
    private String signToken(String tokenType, String jti, long accountId, long userId, long deviceId,
                             OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", JWT_ALGORITHM);
            header.put("typ", JWT_HEADER_TYPE);
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", config.getIssuer());
            claims.put("sub", String.valueOf(userId));
            claims.put("iat", epochSecond(issuedAt));
            claims.put("exp", epochSecond(expiresAt));
            claims.put("jti", jti);
            claims.put("typ", tokenType);
            claims.put("user_id", userId);
            claims.put("account_id", accountId);
            claims.put("device_id", deviceId);
            String headerPart = base64UrlJson(header);
            String payloadPart = base64UrlJson(claims);
            String signingInput = headerPart + "." + payloadPart;
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + BASE64_URL_ENCODER.encodeToString(signature.sign());
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.TOKEN_KEY_UNAVAILABLE);
        }
    }

    /**
     * 验证 access token 的结构、算法、签名、issuer、token 类型和过期时间。
     *
     * @param token 待验证 access token
     * @return 验签后的核心 claim
     */
    public VerifiedAccessToken verifyAccessToken(String token) {
        if (publicKey == null) {
            throw new BusinessException(ResultCode.TOKEN_KEY_UNAVAILABLE);
        }
        String[] parts = splitToken(token);
        Map<String, Object> header = decodeJsonPart(parts[0]);
        requireValue(header, "alg", JWT_ALGORITHM);
        requireValue(header, "typ", JWT_HEADER_TYPE);
        if (!verifySignature(parts)) {
            throw new BusinessException(ResultCode.ACCESS_TOKEN_INVALID);
        }
        Map<String, Object> claims = decodeJsonPart(parts[1]);
        requireValue(claims, "iss", config.getIssuer());
        requireValue(claims, "typ", ACCESS_TOKEN_TYPE);
        long expiresAt = longClaim(claims, "exp");
        if (Instant.now().getEpochSecond() >= expiresAt) {
            throw new BusinessException(ResultCode.ACCESS_TOKEN_EXPIRED);
        }
        long userId = longClaim(claims, "user_id");
        long accountId = longClaim(claims, "account_id");
        long deviceId = longClaim(claims, "device_id");
        String jti = stringClaim(claims, "jti");
        return new VerifiedAccessToken(jti, accountId, userId, deviceId, Instant.ofEpochSecond(expiresAt));
    }

    /**
     * 解析 refresh token 的 jti 和随机 secret。
     *
     * @param token refresh token 明文
     * @return refresh token 核心组成
     */
    public ParsedRefreshToken parseRefreshToken(String token) {
        if (!StringUtils.hasText(token) || !token.startsWith(REFRESH_TOKEN_PREFIX)) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }
        String jti = parts[0].substring(REFRESH_TOKEN_PREFIX.length());
        String secret = parts[1];
        if (!StringUtils.hasText(jti) || jti.length() > 128 || secret.length() > 128) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }
        return new ParsedRefreshToken(jti, secret);
    }

    /**
     * 拆分 JWT 三段结构。
     *
     * @param token JWT 文本
     * @return header、payload、signature 三段
     */
    private String[] splitToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ResultCode.ACCESS_TOKEN_INVALID);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])
                || !StringUtils.hasText(parts[2])) {
            throw new BusinessException(ResultCode.ACCESS_TOKEN_INVALID);
        }
        return parts;
    }

    /**
     * 解码 JWT JSON 段。
     *
     * @param part Base64 URL 编码段
     * @return JSON map
     */
    private Map<String, Object> decodeJsonPart(String part) {
        try {
            return objectMapper.readValue(BASE64_URL_DECODER.decode(part),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.ACCESS_TOKEN_INVALID);
        }
    }

    /**
     * 校验 JWT header 或 claim 中的固定字符串值。
     *
     * @param values JSON map
     * @param key 字段名
     * @param expected 期望值
     */
    private void requireValue(Map<String, Object> values, String key, String expected) {
        Object actual = values.get(key);
        if (!(actual instanceof String value) || !expected.equals(value)) {
            throw new BusinessException(ResultCode.ACCESS_TOKEN_INVALID);
        }
    }

    /**
     * 验证 JWT RS256 签名。
     *
     * @param parts JWT 三段结构
     * @return 签名有效返回 true
     */
    private boolean verifySignature(String[] parts) {
        try {
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(BASE64_URL_DECODER.decode(parts[2]));
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.ACCESS_TOKEN_INVALID);
        }
    }

    /**
     * 读取必填长整型 claim。
     *
     * @param claims JWT claims
     * @param key 字段名
     * @return claim 长整型值
     */
    private long longClaim(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException exception) {
                throw new BusinessException(ResultCode.ACCESS_TOKEN_INVALID);
            }
        }
        throw new BusinessException(ResultCode.ACCESS_TOKEN_INVALID);
    }

    /**
     * 读取必填字符串 claim。
     *
     * @param claims JWT claims
     * @param key 字段名
     * @return claim 字符串值
     */
    private String stringClaim(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text;
        }
        throw new BusinessException(ResultCode.ACCESS_TOKEN_INVALID);
    }

    /**
     * 生成 refresh token 的随机 secret 部分。
     *
     * @return Base64 URL 安全编码后的 256-bit 随机串
     */
    private String generateRefreshTokenSecret() {
        byte[] bytes = new byte[REFRESH_TOKEN_RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return BASE64_URL_ENCODER.encodeToString(bytes);
    }

    /**
     * 将 JSON 对象序列化后执行 Base64 URL 安全编码。
     *
     * @param value 待编码的 JSON 对象
     * @return Base64 URL 安全字符串
     * @throws Exception JSON 序列化失败时抛出
     */
    private String base64UrlJson(Map<String, Object> value) throws Exception {
        return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
    }

    /**
     * 将 OffsetDateTime 转换为 JWT 使用的 Unix 秒级时间戳。
     *
     * @param time 时间对象
     * @return Unix 秒级时间戳
     */
    private long epochSecond(OffsetDateTime time) {
        return time.toInstant().getEpochSecond();
    }

    /**
     * 从 PEM 文本解析 PKCS#8 RSA 私钥。
     *
     * @param pem PEM 格式私钥文本
     * @return RSA 私钥
     * @throws Exception 私钥为空或解析失败时抛出
     */
    private PrivateKey parsePrivateKey(String pem) throws Exception {
        if (!StringUtils.hasText(pem)) {
            throw new IllegalArgumentException("JWT private key must not be blank");
        }
        byte[] keyBytes = Base64.getDecoder().decode(stripPem(pem, "PRIVATE KEY"));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    /**
     * 从 PEM 文本解析 X.509 RSA 公钥。
     *
     * @param pem PEM 格式公钥文本
     * @return RSA 公钥
     * @throws Exception 公钥为空或解析失败时抛出
     */
    private PublicKey parsePublicKey(String pem) throws Exception {
        if (!StringUtils.hasText(pem)) {
            throw new IllegalArgumentException("JWT public key must not be blank");
        }
        byte[] keyBytes = Base64.getDecoder().decode(stripPem(pem, "PUBLIC KEY"));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    /**
     * 去除 PEM 头尾和空白字符，保留中间 Base64 内容。
     *
     * @param pem PEM 文本
     * @param type PEM 类型，如 PRIVATE KEY 或 PUBLIC KEY
     * @return 可直接 Base64 解码的密钥内容
     */
    private String stripPem(String pem, String type) {
        return pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
    }

    /**
     * token 签发结果。
     *
     * @param accessToken access token 明文
     * @param refreshToken refresh token 明文
     * @param refreshTokenSecret refresh token 随机 secret
     * @param accessTokenJti access token jti
     * @param refreshTokenJti refresh token jti
     * @param issuedAt 签发时间
     * @param accessTokenExpiresAt access token 过期时间
     * @param refreshTokenExpiresAt refresh token 过期时间
     * @param expiresIn access token 有效期秒数
     */
    public record IssuedTokens(String accessToken, String refreshToken, String refreshTokenSecret,
                               String accessTokenJti, String refreshTokenJti, OffsetDateTime issuedAt,
                               OffsetDateTime accessTokenExpiresAt, OffsetDateTime refreshTokenExpiresAt,
                               long expiresIn) {

        /**
         * 获取 access token 过期时间 Instant。
         *
         * @return access token 过期时间
         */
        public Instant accessTokenExpiresInstant() {
            return accessTokenExpiresAt.toInstant();
        }
    }

    /**
     * 验签后的 access token 核心信息。
     *
     * @param jti access token 唯一标识
     * @param accountId 账号业务 ID
     * @param userId 用户业务 ID
     * @param deviceId 设备业务 ID
     * @param expiresAt access token 过期时间
     */
    public record VerifiedAccessToken(String jti, long accountId, long userId, long deviceId, Instant expiresAt) {
    }

    /**
     * refresh token 明文解析结果。
     *
     * @param jti refresh token 唯一标识
     * @param secret refresh token 随机 secret
     */
    public record ParsedRefreshToken(String jti, String secret) {
    }
}
