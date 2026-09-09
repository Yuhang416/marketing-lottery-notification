package com.example.lotterysystem.common.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 中奖通知专用的消息通道。
 *
 * <p>它与 {@link DirectRabbitConfig} 的开奖命令队列隔离：开奖消息由原队列消费，
 * 通知任务只进入本队列，便于单独观察和重试。</p>
 */
@Configuration
public class NotificationRabbitConfig {

    public static final String NOTIFICATION_QUEUE_NAME = "NotificationQueue";
    public static final String NOTIFICATION_EXCHANGE_NAME = "NotificationExchange";
    public static final String NOTIFICATION_ROUTING_KEY = "NotificationRouting";

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE_NAME).build();
    }

    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(NOTIFICATION_EXCHANGE_NAME, true, false);
    }

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue())
                .to(notificationExchange())
                .with(NOTIFICATION_ROUTING_KEY);
    }
}
