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

    public static final String NOTIFICATION_DLX_QUEUE_NAME = "NotificationDlxQueue";
    public static final String NOTIFICATION_DLX_EXCHANGE_NAME = "NotificationDlxExchange";
    public static final String NOTIFICATION_DLX_ROUTING_KEY = "NotificationDlxRouting";

    /**
     * 通知队列：消费重试耗尽后进入死信队列，不再被直接丢弃。
     *
     * <p>注意：已经存在的队列不能直接加死信参数，RabbitMQ 会因为参数不一致拒绝声明。
     * 本地环境删掉空的 NotificationQueue 让程序重建即可；生产环境应改用 policy 配置死信，不要删队列。</p>
     */
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE_NAME)
                .deadLetterExchange(NOTIFICATION_DLX_EXCHANGE_NAME)
                .deadLetterRoutingKey(NOTIFICATION_DLX_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue notificationDlxQueue() {
        return QueueBuilder.durable(NOTIFICATION_DLX_QUEUE_NAME).build();
    }

    @Bean
    public DirectExchange notificationDlxExchange() {
        return new DirectExchange(NOTIFICATION_DLX_EXCHANGE_NAME, true, false);
    }

    @Bean
    public Binding notificationDlxBinding() {
        return BindingBuilder.bind(notificationDlxQueue())
                .to(notificationDlxExchange())
                .with(NOTIFICATION_DLX_ROUTING_KEY);
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
