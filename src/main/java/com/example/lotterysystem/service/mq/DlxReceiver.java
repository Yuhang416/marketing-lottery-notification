package com.example.lotterysystem.service.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.example.lotterysystem.common.config.DirectRabbitConfig.*;


/**
 * 旧课程中的“死信自动回灌”演示。
 *
 * <p>默认不启用：毒消息自动回原队列会形成无限重试循环。关闭后，消息会保留在
 * DlxDirectQueue 这个持久化停车场，待排查、修复后再人工重放。</p>
 */
@Component
@ConditionalOnProperty(name = "draw.dlx.auto-republish.enabled", havingValue = "true")
@RabbitListener(queues = DLX_QUEUE_NAME)
public class DlxReceiver {

    private static final Logger logger = LoggerFactory.getLogger(DlxReceiver.class);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @RabbitHandler
    public void process(Map<String, String> message) {
        // 仅在显式开启旧演示开关时才会执行；正常环境绝不自动把死信回灌主队列。
        logger.warn("已启用旧的死信自动回灌演示，message={}", message);
        rabbitTemplate.convertAndSend(EXCHANGE_NAME, ROUTING, message);
    }
}
