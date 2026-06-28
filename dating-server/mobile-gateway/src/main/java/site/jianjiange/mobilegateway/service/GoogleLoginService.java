package site.jianjiange.mobilegateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import site.jianjiange.mobilegateway.client.GoogleTokenClient;
import site.jianjiange.mobilegateway.config.GoogleLoginConfig;
import site.jianjiange.mobilegateway.config.HashConfig;
import site.jianjiange.mobilegateway.dto.GoogleLoginRequest;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.manager.AuthConstants;
import site.jianjiange.mobilegateway.support.HmacHasher;
import site.jianjiange.mobilegateway.vo.LoginTokenVO;

/**
 * Google 登录服务，负责 ID token 校验、GOOGLE 账号和 token 签发编排。
 */
@Service
public class GoogleLoginService {

    private static final String JWT_ALGORITHM = "RS256";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String ISSUER_HTTP = "https://accounts.google.com";
    private static final String ISSUER_PLAIN = "accounts.google.com";
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final GoogleTokenClient googleTokenClient;
    private final GoogleLoginConfig googleLoginConfig;
    private final HmacHasher hmacHasher;
    private final HashConfig hashConfig;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    /**
     * 创建 Google 登录服务。
     *
     * @param googleTokenClient Google JWKS 客户端
     * @param googleLoginConfig Google 登录配置
     * @param hmacHasher HMAC 工具
     * @param hashConfig HMAC 配置
     * @param authService 认证编排服务
     * @param objectMapper JSON 解析器
     */
    public GoogleLoginService(GoogleTokenClient googleTokenClient, GoogleLoginConfig googleLoginConfig,
                              HmacHasher hmacHasher, HashConfig hashConfig, AuthService authService,
                              ObjectMapper objectMapper) {
        this.googleTokenClient = googleTokenClient;
        this.googleLoginConfig = googleLoginConfig;
        this.hmacHasher = hmacHasher;
        this.hashConfig = hashConfig;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 Google ID token 登录或注册。
     *
     * @param request Google 登录请求
     * @return 登录 token 响应
     */
    public LoginTokenVO login(GoogleLoginRequest request) {
        VerifiedGoogleToken verifiedToken = verifyIdToken(request.getGoogleIdToken());
        String subjectHash = hmacHasher.hashGoogleSubject(verifiedToken.subject());
        AuthService.LoginIdentity identity = findOrCreateIdentity(subjectHash);
        authService.ensureUserInitialized(identity, AuthConstants.REGISTER_SOURCE_GOOGLE_LOGIN);
        return authService.issueLoginTokens(identity);
    }

    /**
     * 校验 Google ID token 并提取 subject。
     *
     * @param token Google ID token
     * @return 验证后的 subject
     */
    public VerifiedGoogleToken verifyIdToken(String token) {
        String[] parts = splitToken(token);
        Map<String, Object> header = decodeJsonPart(parts[0]);
        requireHeader(header);
        PublicKey publicKey = googleTokenClient.getPublicKey(stringValue(header.get("kid")));
        if (!verifySignature(parts, publicKey)) {
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
        }
        Map<String, Object> claims = decodeJsonPart(parts[1]);
        requireIssuer(claims);
        requireAudience(claims);
        requireNotExpired(claims);
        String subject = stringValue(claims.get("sub"));
        if (!StringUtils.hasText(subject)) {
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
        }
        return new VerifiedGoogleToken(subject);
    }

    /**
     * 查找或创建 GOOGLE 登录身份，并在唯一键并发冲突时回读已落库身份。
     *
     * @param subjectHash Google subject HMAC hash
     * @return 登录身份信息
     */
    private AuthService.LoginIdentity findOrCreateIdentity(String subjectHash) {
        try {
            return authService.findOrCreateGoogleIdentity(subjectHash, hashConfig.getVersion());
        } catch (DuplicateKeyException exception) {
            return authService.loadGoogleIdentityAfterConflict(subjectHash);
        }
    }

    /**
     * 拆分 JWT 三段结构。
     *
     * @param token JWT 文本
     * @return header、payload、signature 三段
     */
    private String[] splitToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])
                || !StringUtils.hasText(parts[2])) {
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
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
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
        }
    }

    /**
     * 校验 Google token header。
     *
     * @param header header JSON
     */
    private void requireHeader(Map<String, Object> header) {
        if (!JWT_ALGORITHM.equals(stringValue(header.get("alg")))
                || !StringUtils.hasText(stringValue(header.get("kid")))) {
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
        }
    }

    /**
     * 校验 Google issuer。
     *
     * @param claims claim JSON
     */
    private void requireIssuer(Map<String, Object> claims) {
        String issuer = stringValue(claims.get("iss"));
        if (!ISSUER_HTTP.equals(issuer) && !ISSUER_PLAIN.equals(issuer)) {
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
        }
    }

    /**
     * 校验 audience 是否匹配配置的 client id。
     *
     * @param claims claim JSON
     */
    private void requireAudience(Map<String, Object> claims) {
        String expectedAudience = googleLoginConfig.getClientId();
        if (!StringUtils.hasText(expectedAudience)) {
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
        }
        Object audience = claims.get("aud");
        boolean matched = expectedAudience.equals(audience);
        if (!matched && audience instanceof Collection<?> audiences) {
            matched = audiences.stream().anyMatch(expectedAudience::equals);
        }
        if (!matched) {
            throw new BusinessException(ResultCode.GOOGLE_AUDIENCE_MISMATCH);
        }
    }

    /**
     * 校验 token 未过期。
     *
     * @param claims claim JSON
     */
    private void requireNotExpired(Map<String, Object> claims) {
        long expiresAt = longClaim(claims.get("exp"));
        if (Instant.now().getEpochSecond() >= expiresAt) {
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_EXPIRED);
        }
    }

    /**
     * 验证 ID token 签名。
     *
     * @param parts JWT 三段
     * @param publicKey Google 公钥
     * @return 签名是否有效
     */
    private boolean verifySignature(String[] parts, PublicKey publicKey) {
        try {
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(BASE64_URL_DECODER.decode(parts[2]));
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
        }
    }

    /**
     * 读取字符串字段。
     *
     * @param value 字段值
     * @return 字符串或空串
     */
    private String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    /**
     * 读取 long 类型 claim。
     *
     * @param value 字段值
     * @return long 值
     */
    private long longClaim(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException exception) {
                throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
            }
        }
        throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
    }

    /**
     * 验证后的 Google token。
     *
     * @param subject Google subject
     */
    public record VerifiedGoogleToken(String subject) {
    }
}
