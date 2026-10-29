package com.example.lotterysystem.service.enums;

/**
 * 通知任务生命周期。
 */
public enum NotificationOutboxStatusEnum {
    /** 已落库，等待投递器发送至 RabbitMQ。 */
    PENDING,
    /** RabbitMQ 已确认接收，等待通知消费者处理。 */
    PUBLISHED,
    /** 短信或邮件已成功处理。 */
    DONE,
    /** 回查不到对应的中奖记录，任务不应继续发送。 */
    CANCELLED,
    /** 投递重试达到上限仍未成功，已停止重试并告警，需要人工处理。 */
    FAILED
}
