package site.jianjiange.mobilegateway.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import site.jianjiange.mobilegateway.config.HashConfig;
import site.jianjiange.mobilegateway.dto.PhoneLoginRequest;
import site.jianjiange.mobilegateway.manager.AuthConstants;
import site.jianjiange.mobilegateway.vo.LoginTokenVO;

/**
 * 手机验证码登录服务，负责编排验证码校验、PHONE 账号和 token 签发。
 */
@Service
public class PhoneLoginService {

    private final SmsCodeService smsCodeService;
    private final HashConfig hashConfig;
    private final AuthService authService;

    /**
     * 创建手机验证码登录服务。
     *
     * @param smsCodeService 验证码服务
     * @param hashConfig HMAC hash 配置
     * @param authService 认证编排服务
     */
    public PhoneLoginService(SmsCodeService smsCodeService, HashConfig hashConfig, AuthService authService) {
        this.smsCodeService = smsCodeService;
        this.hashConfig = hashConfig;
        this.authService = authService;
    }

    /**
     * 执行手机号验证码登录或注册。
     *
     * @param request 手机登录请求
     * @return 登录 token 响应
     */
    public LoginTokenVO login(PhoneLoginRequest request) {
        String phoneHash = smsCodeService.verifyAndConsume(request.getPhone(), request.getCode());
        AuthService.LoginIdentity identity = findOrCreateIdentity(phoneHash);
        authService.ensureUserInitialized(identity, AuthConstants.REGISTER_SOURCE_PHONE_LOGIN);
        return authService.issueLoginTokens(identity);
    }

    /**
     * 查找或创建 PHONE 登录身份，并在唯一键并发冲突时回读已落库身份。
     *
     * @param phoneHash 手机号 HMAC hash
     * @return 登录身份信息
     */
    private AuthService.LoginIdentity findOrCreateIdentity(String phoneHash) {
        try {
            return authService.findOrCreatePhoneIdentity(phoneHash, hashConfig.getVersion());
        } catch (DuplicateKeyException exception) {
            return authService.loadPhoneIdentityAfterConflict(phoneHash);
        }
    }
}
