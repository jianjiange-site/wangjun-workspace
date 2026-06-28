package site.jianjiange.mobilegateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * refresh token 刷新请求体。
 */
@Getter
@Setter
public class RefreshTokenRequest {

    /**
     * 客户端持有的 refresh token 明文，服务端只保存其随机 secret 的 HMAC hash。
     */
    @NotBlank
    @Size(max = 256)
    @JsonProperty("refresh_token")
    private String refreshToken;
}
