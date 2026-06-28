package site.jianjiange.mobilegateway.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import site.jianjiange.mobilegateway.config.GoogleLoginConfig;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;

/**
 * Google JWKS 客户端，负责拉取、解析并缓存 RSA 公钥。
 */
@Component
public class GoogleTokenClient {

    private static final String KEY_TYPE_RSA = "RSA";
    private static final String JWK_TYPE_RSA = "RSA";
    private static final String JWK_ALGORITHM_RS256 = "RS256";
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final GoogleLoginConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, CachedPublicKey> keyCache = new ConcurrentHashMap<>();
    private final Object refreshLock = new Object();

    /**
     * 创建 Google JWKS 客户端。
     *
     * @param config Google 登录配置
     * @param objectMapper JSON 解析器
     */
    public GoogleTokenClient(GoogleLoginConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getHttpTimeoutMs()))
                .build();
    }

    /**
     * 按 kid 获取 Google RSA 公钥，缓存未命中时刷新 JWKS。
     * 后续可优化为定时刷新kid，要不然所有用户都得等
     *
     * @param kid JWT header.kid
     * @return 可用于验签的公钥
     */
    public PublicKey getPublicKey(String kid) {
        if (!StringUtils.hasText(kid)) {
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
        }
        CachedPublicKey cached = keyCache.get(kid);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.publicKey();
        }
        synchronized (refreshLock) {
            // 假如在得到锁前有另外的线程先得到了PublicKey需要判断一下
            cached = keyCache.get(kid);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                return cached.publicKey();
            }
            refreshJwks();
            cached = keyCache.get(kid);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                return cached.publicKey();
            }
        }
        throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
    }

    /**
     * 拉取 Google JWKS，并将当前返回的 RSA 公钥写入本地缓存。
     */
    private void refreshJwks() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.getJwksUri()))
                    .GET()
                    .timeout(Duration.ofMillis(config.getHttpTimeoutMs()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
            }
            cacheKeys(response.body());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
        }
    }

    /**
     * 解析 JWKS JSON，并缓存其中可用的 RSA key。
     *
     * @param jwksJson JWKS 响应体
     */
    private void cacheKeys(String jwksJson) {
        try {
            JsonNode keysNode = objectMapper.readTree(jwksJson).path("keys");
            if (!keysNode.isArray()) {
                throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
            }
            Instant expiresAt = Instant.now().plusSeconds(config.getJwksCacheTtlSeconds());
            Iterator<JsonNode> iterator = keysNode.elements();
            while (iterator.hasNext()) {
                JsonNode keyNode = iterator.next();
                if (!isUsableRsaKey(keyNode)) {
                    continue;
                }
                String kid = keyNode.path("kid").asText();
                PublicKey publicKey = buildRsaPublicKey(keyNode.path("n").asText(), keyNode.path("e").asText());
                keyCache.put(kid, new CachedPublicKey(publicKey, expiresAt));
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.GOOGLE_TOKEN_INVALID);
        }
    }

    /**
     * 判断 JWK 是否为当前支持的 RSA RS256 公钥。
     *
     * @param keyNode JWK JSON 节点
     * @return 是否可用于验签
     */
    private boolean isUsableRsaKey(JsonNode keyNode) {
        String kid = keyNode.path("kid").asText();
        String keyType = keyNode.path("kty").asText();
        String algorithm = keyNode.path("alg").asText();
        String modulus = keyNode.path("n").asText();
        String exponent = keyNode.path("e").asText();
        return StringUtils.hasText(kid)
                && JWK_TYPE_RSA.equals(keyType)
                && (!StringUtils.hasText(algorithm) || JWK_ALGORITHM_RS256.equals(algorithm))
                && StringUtils.hasText(modulus)
                && StringUtils.hasText(exponent);
    }

    /**
     * 根据 JWK 的 n/e 参数构造 RSA 公钥。
     *
     * @param modulusBase64Url RSA modulus
     * @param exponentBase64Url RSA public exponent
     * @return RSA 公钥
     */
    private PublicKey buildRsaPublicKey(String modulusBase64Url, String exponentBase64Url) throws Exception {
        BigInteger modulus = new BigInteger(1, BASE64_URL_DECODER.decode(modulusBase64Url));
        BigInteger exponent = new BigInteger(1, BASE64_URL_DECODER.decode(exponentBase64Url));
        return KeyFactory.getInstance(KEY_TYPE_RSA).generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    /**
     * 缓存的 Google 公钥。
     *
     * @param publicKey RSA 公钥
     * @param expiresAt 缓存过期时间
     */
    private record CachedPublicKey(PublicKey publicKey, Instant expiresAt) {
    }
}
