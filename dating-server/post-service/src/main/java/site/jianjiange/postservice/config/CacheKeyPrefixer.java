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
        String servicePrefix = servicePrefix();
        if (!servicePrefix.isEmpty() && key.startsWith(servicePrefix)) {
            return keyPrefix + key.substring(servicePrefix.length());
        }
        return keyPrefix + key;
    }

    /**
     * 提取统一前缀中最后一段服务名前缀，用于兼容调用方传入 post:xxx 的场景。
     *
     * @return 服务名前缀，不存在时返回空字符串
     */
    private String servicePrefix() {
        String normalizedPrefix = keyPrefix.endsWith(":")
                ? keyPrefix.substring(0, keyPrefix.length() - 1)
                : keyPrefix;
        int index = normalizedPrefix.lastIndexOf(':');
        if (index < 0 || index == normalizedPrefix.length() - 1) {
            return "";
        }
        return normalizedPrefix.substring(index + 1) + ":";
    }
}
