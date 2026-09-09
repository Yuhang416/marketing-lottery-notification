package com.example.lotterysystem.service.impl;

import com.example.lotterysystem.common.errorcode.ServiceErrorCodeConstants;
import com.example.lotterysystem.common.exception.ServiceException;
import com.example.lotterysystem.common.utils.*;
import com.example.lotterysystem.service.VerificationCodeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class VerificationCodeServiceImpl implements VerificationCodeService {

    // 对于redis里面的key需要标准化：为了区分业务，应该给key定义前缀：
    // VerificationCode_13111111111:1233、User_13111111111:userInfo

    private static final String VERIFICATION_CODE_PREFIX = "VERIFICATION_CODE_";
    private static final Long VERIFICATION_CODE_TIMEOUT = 60L;
    private static final String VERIFICATION_CODE_TEMPLATE_CODE = "SMS_465324787";

    @Autowired
    private SMSUtil smsUtil;
    @Autowired
    private RedisUtil redisUtil;

    @Override
    public void sendVerificationCode(String phoneNumber) {
        // 校验手机号
        if (!RegexUtil.checkMobile(phoneNumber)) {
            throw new ServiceException(ServiceErrorCodeConstants.PHONE_NUMBER_ERROR);
        }

        // 生成随机验证码
        String code = CaptchaUtil.getCaptcha(4);
        // 发送验证码
        // {"code":"xxxx"}
        Map<String, String> map = new HashMap<>();
        map.put("code", code);
        smsUtil.sendMessage(
                VERIFICATION_CODE_TEMPLATE_CODE,
                phoneNumber,
                JacksonUtil.writeValueAsString(map));
        // 缓存验证码
        // 131xxxxxxxx: code
        redisUtil.set(VERIFICATION_CODE_PREFIX + phoneNumber, code, VERIFICATION_CODE_TIMEOUT);
    }

    @Override
    public String getVerificationCode(String phoneNumber) {
        // 校验手机号
        if (!RegexUtil.checkMobile(phoneNumber)) {
            throw new ServiceException(ServiceErrorCodeConstants.PHONE_NUMBER_ERROR);
        }

        return redisUtil.get(VERIFICATION_CODE_PREFIX + phoneNumber);
    }
}
