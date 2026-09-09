package com.example.lotterysystem.service.notification;

import com.example.lotterysystem.dao.dataobject.NotificationOutboxDO;
import com.example.lotterysystem.dao.mapper.NotificationOutboxMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_EXCHANGE_NAME;
import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_ROUTING_KEY;

/**
 * 把已落库的通知任务可靠地投递到通知专用 RabbitMQ 队列。
 */
@Slf4j
@Component
public class NotificationOutboxPublisher {

    @Autowired
    private NotificationOutboxMapper notificationOutboxMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 不能把“调用 send”当成成功；只有 Broker ACK 且消息没有被退回时，
     * 才把通知任务从 PENDING 改为 PUBLISHED。
     */
    @PostConstruct
    void registerPublisherCallbacks() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (correlationData == null) {
                return;
            }

            if (ack && correlationData.getReturned() == null) {
                notificationOutboxMapper.markPublished(correlationData.getId());
                log.info("MQ 已确认接收，notificationId={}",
                        correlationData.getId());
                return;
            }

            // 不更新状态：任务保持 PENDING，下一轮定时扫描会再次投递。
            log.warn("MQ 确认失败，notificationId={}, cause={}",
                    correlationData.getId(), cause);
        });
    }

    /**
     * 每 3 秒扫描一次 PENDING 任务，每次最多投递 20 条。
     */
    @Scheduled(fixedDelay = 3000)
    public void publishPendingTasks() {
        List<NotificationOutboxDO> tasks = notificationOutboxMapper.selectPending(20);
        for (NotificationOutboxDO task : tasks) {
            publish(task);
        }
    }

    private void publish(NotificationOutboxDO task) {
        try {
            rabbitTemplate.convertAndSend(
                    NOTIFICATION_EXCHANGE_NAME,
                    NOTIFICATION_ROUTING_KEY,
                    Map.of("notificationId", task.getNotificationId(), "channel", task.getChannel()),
                    new CorrelationData(task.getNotificationId()));
            log.info("通知任务已提交给 RabbitMQ，等待 Confirm，notificationId={}",
                    task.getNotificationId());
        } catch (Exception e) {
            // 发送异常也不更新状态，任务仍然是 PENDING，下一轮会继续扫描到它。
            log.warn("通知任务发送异常，将在下一轮扫描时重试，notificationId={}",
                    task.getNotificationId(), e);
        }
    }
}
