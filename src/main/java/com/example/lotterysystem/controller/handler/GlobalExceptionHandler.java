package com.example.lotterysystem.controller.handler;

import com.example.lotterysystem.common.errorcode.GlobalErrorCodeConstants;
import com.example.lotterysystem.common.exception.ControllerException;
import com.example.lotterysystem.common.exception.ServiceException;
import com.example.lotterysystem.common.pojo.CommonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@RestControllerAdvice  // 可以捕获全局抛得异常
public class GlobalExceptionHandler {

    private final static Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);


    @ExceptionHandler(value = ServiceException.class)
    public CommonResult<?> serviceException(ServiceException e) {
        // 打错误日志
        logger.error("serviceException:", e);
        // 构造错误结果
        return CommonResult.error(
                GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getCode(),
                e.getMessage());
    }

    @ExceptionHandler(value = ControllerException.class)
    public CommonResult<?> controllerException(ControllerException e) {
        // 打错误日志
        logger.error("controllerException:", e);
        // 构造错误结果
        return CommonResult.error(
                GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getCode(),
                e.getMessage());
    }

    @ExceptionHandler(value = Exception.class)
    public CommonResult<?> exception(Exception e) {
        // 打错误日志
        logger.error("服务异常:", e);
        // 构造错误结果
        return CommonResult.error(
                GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getCode(),
                e.getMessage());
    }


}
