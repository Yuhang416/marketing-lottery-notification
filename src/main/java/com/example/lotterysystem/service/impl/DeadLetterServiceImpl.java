package com.example.lotterysystem.service.impl;

import com.example.lotterysystem.common.errorcode.ServiceErrorCodeConstants;
import com.example.lotterysystem.common.exception.ServiceException;
import com.example.lotterysystem.common.utils.JacksonUtil;
import com.example.lotterysystem.dao.dataobject.DeadLetterRecordDO;
import com.example.lotterysystem.dao.mapper.DeadLetterRecordMapper;
import com.example.lotterysystem.dao.mapper.NotificationOutboxMapper;
import com.example.lotterysystem.service.DeadLetterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_QUEUE_NAME;

@Service
public class DeadLetterServiceImpl implements DeadLetterService {

    private static final Logger logger = LoggerFactory.getLogger(DeadLetterServiceImpl.class);
    /** 重放消息 CorrelationData id 的前缀，Outbox 投递器的确认回调会忽略它。 */
    private static final String REPLAY_CORRELATION_PREFIX = "replay:";
    private static final String STATUS_PENDING = "PENDING";

    @Autowired
    private DeadLetterRecordMapper deadLetterRecordMapper;
    @Autowired
    private NotificationOutboxMapper notificationOutboxMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Value("${dead-letter.replay-confirm-timeout-ms:3000}")
    private long replayConfirmTimeoutMs;

    @Override
    public void record(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        // 先打日志再落库：落库失败时，消息内容至少还留在日志里
        logger.error("收到死信，需要人工处理：queue={}, body={}",
                message.getMessageProperties().getConsumerQueue(), body);

        DeadLetterRecordDO record = new DeadLetterRecordDO();
        record.setMessageBody(body);
        record.setStatus(STATUS_PENDING);
        // x-death 由 RabbitMQ 在消息进入死信时写入，第一条是最近一次
        List<Map<String, ?>> deaths = message.getMessageProperties().getXDeathHeader();
        Map<String, ?> death = deaths == null || deaths.isEmpty() ? Map.of() : deaths.get(0);
        record.setSourceQueue(stringOf(death.get("queue")));
        record.setSourceExchange(stringOf(death.get("exchange")));
        Object routingKeys = death.get("routing-keys");
        record.setSourceRoutingKey(routingKeys instanceof List<?> keys && !keys.isEmpty()
                ? String.valueOf(keys.get(0)) : "");
        record.setReason(death.get("reason") == null ? null : String.valueOf(death.get("reason")));
        record.setDeathCount(death.get("count") instanceof Number n ? n.longValue() : null);
        record.setFirstDeathTime(death.get("time") instanceof Date d ? d : null);
        deadLetterRecordMapper.insert(record);

        // 通知类的死信：任务停止处理，等人工重放。
        // 消息体可能本身就是坏的，解析失败只打日志，不能抛出：抛出会让这条死信被重试、重复落库
        if (NOTIFICATION_QUEUE_NAME.equals(record.getSourceQueue())) {
            try {
                Map<?, ?> payload = JacksonUtil.readValue(body, Map.class);
                String notificationId = String.valueOf(payload.get("notificationId"));
                notificationOutboxMapper.markFailedByNotificationId(notificationId,
                        "通知消费重试耗尽，进入死信，死信记录 id=" + record.getId());
            } catch (Exception e) {
                logger.error("通知死信的消息体无法解析，没有更新任务状态，死信记录 id={}", record.getId(), e);
            }
        }
        logger.error("死信已记录，死信记录 id={}, queue={}, reason={}, count={}",
                record.getId(), record.getSourceQueue(), record.getReason(), record.getDeathCount());
    }

    @Override
    public void replay(Long id) {
        DeadLetterRecordDO record = deadLetterRecordMapper.selectById(id);
        if (record == null) {
            throw new ServiceException(ServiceErrorCodeConstants.DEAD_LETTER_NOT_FOUND);
        }
        // 先用条件更新占住这条记录，同一条记录被同时点两次重放时只有一次生效
        if (deadLetterRecordMapper.markReplayed(id, new Date()) == 0) {
            throw new ServiceException(ServiceErrorCodeConstants.DEAD_LETTER_ALREADY_REPLAYED);
        }

        try {
            // 按原交换机和路由键发回去。开奖消息由开奖结算的条件更新保证幂等；
            // 通知消息对应的任务是 FAILED，消费者允许重新抢占发送
            Map<?, ?> payload = JacksonUtil.readValue(record.getMessageBody(), Map.class);
            CorrelationData correlationData = new CorrelationData(REPLAY_CORRELATION_PREFIX + id);
            rabbitTemplate.convertAndSend(record.getSourceExchange(), record.getSourceRoutingKey(),
                    payload, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(replayConfirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlationData.getReturned() != null) {
                throw new IllegalStateException("Broker 没有确认消息进入队列，ack=" + confirm.isAck()
                        + ", returned=" + correlationData.getReturned());
            }
        } catch (Exception e) {
            deadLetterRecordMapper.revertReplayed(id);
            logger.error("死信重放失败，死信记录 id={}", id, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ServiceException(ServiceErrorCodeConstants.DEAD_LETTER_REPLAY_FAILED);
        }
        logger.info("死信已重放，死信记录 id={}, exchange={}, routingKey={}",
                id, record.getSourceExchange(), record.getSourceRoutingKey());
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
