package com.example.lotterysystem.common.exception;

import com.example.lotterysystem.common.errorcode.ErrorCode;
import lombok.Data;
import lombok.EqualsAndHashCode;

// @Data 生成自己的 equals  hashcode
// 不写@EqualsAndHashCode(callSuper = true)  可能会出现问题
@Data
@EqualsAndHashCode(callSuper = true)
public class ServiceException extends RuntimeException{
    /**
     * 异常码
     * @see com.example.lotterysystem.common.errorcode.ServiceErrorCodeConstants
     */
    private Integer code;

    /**
     * 异常消息
     */
    private String message;

    public ServiceException() {

    }

    public ServiceException(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public ServiceException(ErrorCode errorCode) {
        this.code = errorCode.getCode();
        this.message = errorCode.getMsg();
    }
}
