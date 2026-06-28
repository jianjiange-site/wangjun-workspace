package site.jianjiange.mobilegateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Google 登录请求体。
 */
@Getter
@Setter
public class GoogleLoginRequest {

    /**
     * 客户端从 Google 获取的 ID token。
     */
    @NotBlank
    @Size(max = 4096)
    @JsonProperty("google_id_token")
    private String googleIdToken;
}
