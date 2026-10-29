package com.example.lotterysystem.common.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 死信消费者专用的监听容器。
 *
 * <p>死信里最典型的就是格式不对、转换失败的消息。默认容器用 JSON 转换器，遇到这类消息会再次转换失败，
 * 重试耗尽后被丢弃（死信队列本身没有再配死信），死信就丢了。这里换成 SimpleMessageConverter，
 * 不解析 JSON，任何格式的消息都能收下来落库。</p>
 *
 * <p>不能再声明一个 MessageConverter 类型的 bean：Spring Boot 只有在它唯一时才会自动使用，
 * 多一个会让全局的 JSON 转换器失效。所以只在这个容器工厂里替换。</p>
 */
@Configuration
public class DeadLetterRabbitConfig {

    public static final String DEAD_LETTER_CONTAINER_FACTORY = "deadLetterContainerFactory";

    @Bean(DEAD_LETTER_CONTAINER_FACTORY)
    public SimpleRabbitListenerContainerFactory deadLetterContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        // 先套用 spring.rabbitmq.listener.simple.* 的公共配置（确认模式、重试等），再替换转换器
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(new SimpleMessageConverter());
        return factory;
    }
}
