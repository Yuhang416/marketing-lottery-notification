package com.example.lotterysystem.repro;

import com.example.lotterysystem.common.utils.JacksonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_EXCHANGE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * T4 永久失败：删掉通知交换机到通知队列的绑定，通知消息每次都会被退回。
 * 统计一段时间内每个任务被投递（并被退回）的次数、每次的间隔、最后的状态。
 * 结束前先删掉测试任务，再恢复绑定，避免恢复后这些任务被真的投递出去。
 *
 * <p>为了一分钟内跑完，测试里把重试上限调成 4 次、退避调成 3、6、12 秒、不加抖动；
 * 正式配置是 8 次、10 秒到 5 分钟。步骤 4 修复后加了断言：到上限后停止，状态变为 FAILED。</p>
 *
 * <p>运行：./mvnw test -Prepro -Dtest=T4UnroutableNotificationTest -Dtasks=5 -Dseconds=60</p>
 */
@TestPropertySource(properties = {
        "notification.outbox.max-attempts=4",
        "notification.outbox.backoff-seconds=3,6,12",
        "notification.outbox.backoff-jitter-ratio=0"
})
class T4UnroutableNotificationTest extends ReproSupport {

    private static final String NAME = "T4";
    private static final int MAX_ATTEMPTS = 4;

    @Autowired
    @Qualifier("notificationBinding")
    private Binding notificationBinding;

    @Test
    void notificationTaskThatCanNeverBeRouted() {
        int taskCount = Integer.getInteger("tasks", 5);
        int seconds = Integer.getInteger("seconds", 60);
        cleanupReproData();
        startReport(NAME, "tasks=" + taskCount + " seconds=" + seconds
                + " 重试上限=" + MAX_ATTEMPTS + " 退避=3,6,12 秒 已删除 NotificationExchange -> NotificationQueue 的绑定");

        DrawFixture f = createDrawFixture(NAME);
        Map<String, List<Long>> returnTimes = new ConcurrentHashMap<>();
        List<String> ids = new ArrayList<>();
        for (long winnerId = 1; winnerId <= taskCount; winnerId++) {
            String id = insertTask(f, winnerId, "SMS", "PENDING");
            ids.add(id);
            returnTimes.put(id, new CopyOnWriteArrayList<>());
        }

        onReturned(r -> {
            if (!NOTIFICATION_EXCHANGE_NAME.equals(r.getExchange())) {
                return;
            }
            Map<?, ?> body = JacksonUtil.readValue(
                    new String(r.getMessage().getBody(), StandardCharsets.UTF_8), Map.class);
            List<Long> times = returnTimes.get(String.valueOf(body.get("notificationId")));
            if (times != null) {
                times.add(System.currentTimeMillis());
            }
        });
        rabbitAdmin.removeBinding(notificationBinding);
        try {
            sleep(seconds * 1000L);

            int total = 0;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String id : ids) {
                List<Long> times = returnTimes.get(id);
                total += times.size();
                List<String> gaps = new ArrayList<>();
                for (int i = 1; i < times.size(); i++) {
                    gaps.add(String.format("%.1f", (times.get(i) - times.get(i - 1)) / 1000.0));
                }
                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "select status, attempt_count, last_error from notification_outbox where notification_id = ?", id);
                row.put("deliveries", times.size());
                rows.add(row);
                report(NAME, String.format("任务 %s：被投递并退回 %d 次，每次间隔（秒）[%s]，状态=%s，attempt_count=%s，last_error=%s",
                        id.substring(0, 8), times.size(), String.join(", ", gaps),
                        row.get("status"), row.get("attempt_count"), row.get("last_error")));
            }
            Map<Object, Long> statuses = rows.stream()
                    .collect(Collectors.groupingBy(r -> r.get("status"), Collectors.counting()));
            report(NAME, "---- 汇总 ----");
            report(NAME, seconds + " 秒内 " + taskCount + " 个任务共被投递 " + total + " 次，平均每个任务 "
                    + String.format("%.1f", total / (double) taskCount) + " 次；最终状态 " + statuses);

            jdbcTemplate.update("delete from notification_outbox where activity_id = ?", f.activityId());

            // 步骤 4 的验收条件：投递到上限后停止，状态变为 FAILED，并记录失败原因
            for (Map<String, Object> row : rows) {
                assertEquals(MAX_ATTEMPTS, row.get("deliveries"), "投递次数");
                assertEquals("FAILED", row.get("status"), "最终状态");
                assertEquals(MAX_ATTEMPTS, ((Number) row.get("attempt_count")).intValue(), "attempt_count");
                assertNotNull(row.get("last_error"), "last_error");
            }
        } finally {
            rabbitAdmin.declareBinding(notificationBinding);
            onReturned(null);
        }
    }
}
