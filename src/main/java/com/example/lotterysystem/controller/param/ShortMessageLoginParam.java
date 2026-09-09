package com.example.lotterysystem.controller.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ShortMessageLoginParam extends UserLoginParam {

    /**
     * 电话
     */
    @NotBlank(message = "电话不能为空！")
    private String loginMobile;

    /**
     * 验证码
     */
    @NotBlank(message = "验证码不能为空！")
    private String verificationCode;

}
