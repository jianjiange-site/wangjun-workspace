package site.jianjiange.postservice.config;

/**
 * Redis 业务 Key 前缀工具，保证业务写入时统一带上隔离前缀。
 */
public class CacheKeyPrefixer {

    private final String keyPrefix;

    /**
     * 创建 Key 前缀处理器。
     *
     * @param keyPrefix 统一业务 Key 前缀
     */
    public CacheKeyPrefixer(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    /**
     * 为业务 Key 补齐统一前缀，已包含前缀时保持原值。
     *
     * @param key 原始业务 Key
     * @return 带统一前缀的业务 Key
     */
    public String prefix(String key) {
        if (key.startsWith(keyPrefix)) {
            return key;
        }
        return keyPrefix + key;
    }
}
