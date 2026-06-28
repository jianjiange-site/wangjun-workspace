package site.jianjiange.mobilegateway.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * MVP 阶段短信供应商 mock 实现。
 */
@Component
public class MockSmsProviderClient implements SmsProviderClient {

    private final Map<String, String> lastCodeByPhone = new ConcurrentHashMap<>();

    /**
     * 记录最后一次发送的验证码，便于本地和测试环境验收。
     *
     * @param phone 规范化后的手机号
     * @param code 6 位验证码
     */
    @Override
    public void sendCode(String phone, String code) {
        lastCodeByPhone.put(phone, code);
    }

    /**
     * 读取指定手机号最后一次 mock 发送的验证码。
     *
     * @param phone 规范化后的手机号
     * @return 最后发送的验证码，不存在时返回 null
     */
    public String getLastCode(String phone) {
        return lastCodeByPhone.get(phone);
    }
}
