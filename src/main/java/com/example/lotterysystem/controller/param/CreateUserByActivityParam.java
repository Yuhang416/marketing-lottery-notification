package com.example.lotterysystem.controller.param;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateUserByActivityParam implements Serializable {

    /**
     * 活动关联的人员id
     */
    @NotNull(message = "活动关联的人员id不能为空！")
    private Long userId;
    /**
     * 姓名
     */
    @NotBlank(message = "姓名不能为空！")
    private String userName;

}
