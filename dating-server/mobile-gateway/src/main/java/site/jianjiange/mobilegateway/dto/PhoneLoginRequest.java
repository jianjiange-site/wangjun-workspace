package site.jianjiange.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 手机验证码登录请求体。
 */
@Getter
@Setter
public class PhoneLoginRequest {

    /**
     * E.164 格式手机号，例如 +8613800000000。
     */
    @NotBlank
    @Size(max = 32)
    private String phone;

    /**
     * 客户端收到的 6 位短信验证码。
     */
    @NotBlank
    @Size(min = 6, max = 6)
    private String code;
}
