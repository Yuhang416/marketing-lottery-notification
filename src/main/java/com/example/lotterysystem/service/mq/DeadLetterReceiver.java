package com.example.lotterysystem.service.mq;

import com.example.lotterysystem.service.DeadLetterService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.example.lotterysystem.common.config.DeadLetterRabbitConfig.DEAD_LETTER_CONTAINER_FACTORY;
import static com.example.lotterysystem.common.config.DirectRabbitConfig.DLX_QUEUE_NAME;
import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_DLX_QUEUE_NAME;

/**
 * 开奖、通知两个死信队列的消费者：只记录和告警，不自动回灌。
 *
 * <p>毒消息自动发回原队列只会形成无限重试循环。死信落库后由人工排查，
 * 修复后通过 {@code POST /dead-letters/{id}/replay} 重放。</p>
 */
@Component
public class DeadLetterReceiver {

    @Autowired
    private DeadLetterService deadLetterService;

    /**
     * 直接接收原始 Message，才能读到 x-death 头。
     * 用专门的容器工厂（不解析 JSON），格式错误的毒消息也能被记录下来。
     */
    @RabbitListener(queues = {DLX_QUEUE_NAME, NOTIFICATION_DLX_QUEUE_NAME},
            containerFactory = DEAD_LETTER_CONTAINER_FACTORY)
    public void process(Message message) {
        deadLetterService.record(message);
    }
}
