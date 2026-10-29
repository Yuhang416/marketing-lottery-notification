package com.example.lotterysystem.service.notification;

import com.example.lotterysystem.dao.dataobject.NotificationOutboxDO;
import com.example.lotterysystem.dao.mapper.NotificationOutboxMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.example.lotterysystem.common.config.DirectRabbitConfig.DRAW_CORRELATION_PREFIX;
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
    @Autowired
    private NotificationOutboxClaimer notificationOutboxClaimer;
    @Value("${notification.outbox.batch-size:20}")
    private int batchSize;

    /**
     * 不能把“调用 send”当成成功；只有 Broker ACK 且消息没有被退回时，
     * 才把通知任务从 PENDING 改为 PUBLISHED。
     */
    @PostConstruct
    void registerPublisherCallbacks() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            // 开奖消息的确认也会进到这个回调：它由开奖接口自己通过 CorrelationData 等结果，这里忽略
            if (correlationData == null
                    || correlationData.getId().startsWith(DRAW_CORRELATION_PREFIX)) {
                return;
            }

            if (ack && correlationData.getReturned() == null) {
                notificationOutboxMapper.markPublished(correlationData.getId());
                log.info("MQ 已确认接收，notificationId={}",
                        correlationData.getId());
                return;
            }

            // 只记录原因：任务保持 PENDING，领取时设置的租约到期后会被重新领取投递。
            String error = correlationData.getReturned() != null
                    ? "消息被退回：" + correlationData.getReturned().getReplyText()
                    : "Broker 拒收：" + cause;
            notificationOutboxMapper.recordError(correlationData.getId(),
                    NotificationOutboxClaimer.truncate(error));
            log.warn("MQ 确认失败，notificationId={}, error={}",
                    correlationData.getId(), error);
        });
    }

    /**
     * 默认每 3 秒领取一次到期任务，每次最多 20 条；两个值都可以在配置里调整。
     * 领取在 claimer 的事务里完成并提交，投递在事务外面做。
     */
    @Scheduled(fixedDelayString = "${notification.outbox.publish-interval-ms:3000}",
            initialDelayString = "${notification.outbox.publish-initial-delay-ms:0}")
    public void publishPendingTasks() {
        List<NotificationOutboxDO> tasks = notificationOutboxClaimer.claim(batchSize);
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
            // 发送异常也只记录原因，任务仍然是 PENDING，租约到期后会被重新领取。
            notificationOutboxMapper.recordError(task.getNotificationId(),
                    NotificationOutboxClaimer.truncate("发送异常：" + e.getMessage()));
            log.warn("通知任务发送异常，租约到期后重试，notificationId={}",
                    task.getNotificationId(), e);
        }
    }
}
