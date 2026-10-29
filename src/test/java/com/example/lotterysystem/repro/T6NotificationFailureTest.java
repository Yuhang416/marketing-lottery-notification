package com.example.lotterysystem.repro;

import com.example.lotterysystem.common.utils.SMSUtil;
import com.example.lotterysystem.service.DeadLetterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.List;
import java.util.Map;

import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_DLX_QUEUE_NAME;
import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_QUEUE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * T6 通知失败耗尽：mock 短信工具固定抛异常。任务走完整链路（PENDING → 投递 → 消费），
 * 统计实际尝试发送了几次、消息最后去了哪里、任务最后停在什么状态。
 *
 * <p>步骤 5 修复后：重试耗尽的消息进入通知死信队列，死信消费者写死信记录、把任务改成 FAILED。
 * 测试接着把发送恢复正常，调用人工重放，确认任务最终发出并变为 DONE。</p>
 *
 * <p>运行：./mvnw test -Prepro -Dtest=T6NotificationFailureTest -Drounds=3</p>
 */
class T6NotificationFailureTest extends ReproSupport {

    private static final String NAME = "T6";

    @SpyBean
    private SMSUtil smsUtil;
    @Autowired
    private DeadLetterService deadLetterService;

    @BeforeEach
    void alwaysFail() {
        doThrow(new IllegalStateException("复现测试：模拟短信发送失败"))
                .when(smsUtil).sendMessage(anyString(), anyString(), anyString(), any());
    }

    @AfterEach
    void resetSpy() {
        Mockito.reset(smsUtil);
    }

    @Test
    void notificationAlwaysFails() {
        int rounds = Integer.getInteger("rounds", 3);
        cleanupReproData();
        startReport(NAME, "rounds=" + rounds + " 短信发送固定抛异常，然后恢复正常并人工重放");

        for (int i = 1; i <= rounds; i++) {
            alwaysFail();
            DrawFixture f = createDrawFixture(NAME);
            insertWinningRecord(f);
            String notificationId = insertTask(f, f.userId(), "SMS", "PENDING");

            long start = System.currentTimeMillis();
            awaitStable(() -> List.of(taskStatus(notificationId), attempts(f.phone()),
                    deadLetterIds(notificationId)), 6000, 60_000);
            int attempts = attempts(f.phone());
            String status = taskStatus(notificationId);
            List<Long> deadLetters = deadLetterIds(notificationId);
            Map<String, Object> deadLetter = deadLetters.isEmpty() ? Map.of()
                    : jdbcTemplate.queryForMap("select source_queue, reason, death_count from dead_letter_record where id = ?",
                    deadLetters.get(0));
            report(NAME, String.format("第 %d 轮：尝试发送 %d 次（从写入任务到状态稳定约 %.1f 秒，含 6 秒稳定判断），状态=%s，"
                            + "通知队列剩余=%d，通知死信队列剩余=%d，死信记录=%s %s",
                    i, attempts, (System.currentTimeMillis() - start) / 1000.0, status,
                    queueCount(NOTIFICATION_QUEUE_NAME), queueCount(NOTIFICATION_DLX_QUEUE_NAME),
                    deadLetters, deadLetter));

            // 步骤 5 的验收条件：重试耗尽后进入死信并记录，任务变为 FAILED
            assertEquals(5, attempts, "尝试发送次数");
            assertEquals("FAILED", status, "进入死信后的任务状态");
            assertEquals(1, deadLetters.size(), "死信记录条数");
            assertEquals(NOTIFICATION_QUEUE_NAME, deadLetter.get("source_queue"), "死信来源队列");

            // 发送恢复正常后人工重放，任务应该发出并结案
            Mockito.reset(smsUtil);
            deadLetterService.replay(deadLetters.get(0));
            awaitStable(() -> List.of(taskStatus(notificationId), attempts(f.phone())), 2000, 30_000);
            report(NAME, String.format("第 %d 轮重放后：状态=%s，重放后发送次数=%d，死信记录状态=%s",
                    i, taskStatus(notificationId), attempts(f.phone()),
                    jdbcTemplate.queryForObject("select status from dead_letter_record where id = ?",
                            String.class, deadLetters.get(0))));
            assertEquals("DONE", taskStatus(notificationId), "重放后的任务状态");
            assertEquals(1, attempts(f.phone()), "重放后的发送次数");
        }
    }

    private int attempts(String phone) {
        return (int) Mockito.mockingDetails(smsUtil).getInvocations().stream()
                .filter(inv -> "sendMessage".equals(inv.getMethod().getName()))
                .filter(inv -> inv.getArguments().length == 4)
                .filter(inv -> phone.equals(inv.getArguments()[1]))
                .count();
    }

    private List<Long> deadLetterIds(String notificationId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from dead_letter_record where message_body like ? order by id",
                Long.class, "%" + notificationId + "%");
        assertNotNull(ids);
        return ids;
    }
}
