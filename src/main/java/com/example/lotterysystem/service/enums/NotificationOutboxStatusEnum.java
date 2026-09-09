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
    /** 本次开奖被补偿，任务不应继续发送。 */
    CANCELLED
}
