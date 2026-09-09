package com.example.lotterysystem.common.pojo;

import com.example.lotterysystem.common.errorcode.ErrorCode;
import com.example.lotterysystem.common.errorcode.GlobalErrorCodeConstants;
import lombok.Data;
import org.springframework.util.Assert;

import java.io.Serializable;

@Data
public class CommonResult<T> implements Serializable {

    /**
     * 返回的错误码
     */
    private Integer code;

    /**
     * 正常返回数据
     */
    private T data;

    /**
     * 错误码描述
     */
    private String msg;

    public static <T> CommonResult<T> success(T data) {
        CommonResult<T> result = new CommonResult<>();
        result.code = GlobalErrorCodeConstants.SUCCESS.getCode();
        result.data = data;
        result.msg = "";
        return result;
    }

    public static <T> CommonResult<T> error(Integer code, String msg) {
        Assert.isTrue(!GlobalErrorCodeConstants.SUCCESS.getCode().equals(code),
                "code 不是错误的异常");
        CommonResult<T> result = new CommonResult<>();
        result.code = code;
        result.msg = msg;
        return result;
    }

    public static <T> CommonResult<T> error(ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMsg());
    }

}
