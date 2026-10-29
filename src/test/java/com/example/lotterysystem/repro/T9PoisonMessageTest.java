package com.example.lotterysystem.repro;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.lotterysystem.common.config.DirectRabbitConfig.DLX_QUEUE_NAME;
import static com.example.lotterysystem.common.config.DirectRabbitConfig.EXCHANGE_NAME;
import static com.example.lotterysystem.common.config.DirectRabbitConfig.QUEUE_NAME;
import static com.example.lotterysystem.common.config.DirectRabbitConfig.ROUTING;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T9 格式错误的毒消息：往开奖队列发一条无法转换的消息，开奖消费者转换失败、重试耗尽后进入死信，
 * 死信消费者必须能把它记录下来。
 *
 * <p>在 Compose 环境验证时发现：死信消费者原来也用 JSON 转换器，遇到这类消息会再次转换失败，
 * 重试耗尽后被丢弃，死信记录里什么都没有。改成专用的容器工厂（不解析 JSON）后加了这个测试。</p>
 *
 * <p>运行：./mvnw test -Prepro -Dtest=T9PoisonMessageTest</p>
 */
class T9PoisonMessageTest extends ReproSupport {

    private static final String NAME = "T9";

    @Test
    void poisonMessageEndsUpInDeadLetterRecords() {
        String marker = "repro-poison-" + UUID.randomUUID();
        startReport(NAME, "往开奖队列发一条格式错误的消息：" + marker);
        jdbcTemplate.update("delete from dead_letter_record where message_body like 'repro-poison-%'");

        // 声明是 JSON、内容却解析不了：开奖消费者和原来的死信消费者都用 JSON 转换器，都会转换失败。
        // 用 text/plain 复现不了，JSON 转换器遇到非 JSON 类型会直接把消息体当字符串返回
        Message poison = MessageBuilder.withBody((marker + " {这不是合法的 JSON").getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader("__TypeId__", "java.util.HashMap")
                .build();
        rabbitTemplate.send(EXCHANGE_NAME, ROUTING, poison);

        awaitStable(() -> List.of(recordIds(marker), queueCount(QUEUE_NAME), queueCount(DLX_QUEUE_NAME)),
                8000, 60_000);
        List<Long> ids = recordIds(marker);
        Map<String, Object> row = ids.isEmpty() ? Map.of()
                : jdbcTemplate.queryForMap("select source_queue, source_exchange, reason, status from dead_letter_record where id = ?",
                ids.get(0));
        report(NAME, "死信记录 " + ids + " " + row);
        jdbcTemplate.update("delete from dead_letter_record where message_body like 'repro-poison-%'");

        assertEquals(1, ids.size(), "死信记录条数");
        assertEquals(QUEUE_NAME, row.get("source_queue"), "死信来源队列");
        assertEquals("PENDING", row.get("status"), "死信记录状态");
    }

    private List<Long> recordIds(String marker) {
        return jdbcTemplate.queryForList(
                "select id from dead_letter_record where message_body like ? order by id", Long.class, marker + "%");
    }
}
