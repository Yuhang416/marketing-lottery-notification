package com.example.lotterysystem.service;

import org.springframework.amqp.core.Message;

public interface DeadLetterService {

    /**
     * 记录一条死信：落库并打告警日志。通知类的死信同时把对应任务标成 FAILED。
     *
     * @param message 死信队列里的原始消息，带 x-death 头
     */
    void record(Message message);

    /**
     * 人工重放：把死信记录里的原消息发回原来的交换机，等 Broker 确认后返回。
     *
     * @param id 死信记录 id
     */
    void replay(Long id);
}
