package com.example.lotterysystem.controller;

import com.example.lotterysystem.common.pojo.CommonResult;
import com.example.lotterysystem.service.DeadLetterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 死信人工重放。接口在登录拦截范围内，需要登录后调用。
 */
@RestController
public class DeadLetterController {

    private static final Logger logger = LoggerFactory.getLogger(DeadLetterController.class);

    @Autowired
    private DeadLetterService deadLetterService;

    @PostMapping("/dead-letters/{id}/replay")
    public CommonResult<Boolean> replay(@PathVariable("id") Long id) {
        logger.info("人工重放死信，id={}", id);
        deadLetterService.replay(id);
        return CommonResult.success(true);
    }
}
