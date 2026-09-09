package com.example.lotterysystem.common.exception;

import com.example.lotterysystem.common.errorcode.ErrorCode;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ControllerException extends RuntimeException{
    /**
     * 异常码
     * @see com.example.lotterysystem.common.errorcode.ControllerErrorCodeConstants
     */
    private Integer code;

    /**
     * 异常消息
     */
    private String message;

    // 为了序列化
    public ControllerException() {

    }

    public ControllerException(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public ControllerException(ErrorCode errorCode) {
        this.code = errorCode.getCode();
        this.message = errorCode.getMsg();
    }
}
