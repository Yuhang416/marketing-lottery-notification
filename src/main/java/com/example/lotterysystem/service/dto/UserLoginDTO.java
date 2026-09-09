package com.example.lotterysystem.service.dto;

import com.example.lotterysystem.service.enums.UserIdentityEnum;
import lombok.Data;

@Data
public class UserLoginDTO {
    /**
     * JWT 令牌
     */
    private String token;

    /**
     * 登录人员身份
     */
    private UserIdentityEnum identity;
}
