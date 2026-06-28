package site.jianjiange.mobilegateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 发送手机验证码请求体。
 */
@Getter
@Setter
public class SendSmsCodeRequest {

    /**
     * E.164 格式手机号，例如 +8613800000000。
     */
    @NotBlank
    @Size(max = 32)
    private String phone;
}
