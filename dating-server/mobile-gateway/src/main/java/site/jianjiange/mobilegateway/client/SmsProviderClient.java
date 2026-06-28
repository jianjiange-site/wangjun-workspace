package site.jianjiange.mobilegateway.client;

/**
 * 短信供应商客户端抽象。
 */
public interface SmsProviderClient {

    /**
     * 发送短信验证码。
     *
     * @param phone 规范化后的手机号
     * @param code 6 位验证码
     */
    void sendCode(String phone, String code);
}
