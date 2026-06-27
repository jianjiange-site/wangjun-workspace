package site.jianjiange.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 设备号快速登录请求体。
 */
@Getter
@Setter
public class DeviceLoginRequest {

    /**
     * 客户端生成并持久化的设备唯一标识，服务端只保存其 HMAC hash。
     */
    @NotBlank
    @Size(min = 16, max = 128)
    private String deviceKey;

    /**
     * 设备展示名称，可为空。
     */
    @Size(max = 128)
    private String deviceName;

    /**
     * 客户端类型，例如 android、ios 或 h5。
     */
    @Size(max = 32)
    private String clientType;

}
