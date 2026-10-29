package com.example.lotterysystem.repro;

import com.example.lotterysystem.common.utils.JacksonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_EXCHANGE_NAME;

/**
 * T4 永久失败：删掉通知交换机到通知队列的绑定，通知消息每次都会被退回，
 * 任务一直停在 PENDING。统计一段时间内每个任务被投递（并被退回）了几次。
 * 结束前先删掉测试任务，再恢复绑定，避免恢复后这些任务被真的投递出去。
 *
 * <p>运行：./mvnw test -Prepro -Dtest=T4UnroutableNotificationTest -Dtasks=5 -Dseconds=60</p>
 */
class T4UnroutableNotificationTest extends ReproSupport {

    private static final String NAME = "T4";

    @Autowired
    @Qualifier("notificationBinding")
    private Binding notificationBinding;

    @Test
    void notificationTaskThatCanNeverBeRouted() {
        int taskCount = Integer.getInteger("tasks", 5);
        int seconds = Integer.getInteger("seconds", 60);
        cleanupReproData();
        startReport(NAME, "tasks=" + taskCount + " seconds=" + seconds
                + " 已删除 NotificationExchange -> NotificationQueue 的绑定");

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
            for (String id : ids) {
                List<Long> times = returnTimes.get(id);
                total += times.size();
                String interval = times.size() < 2 ? "-"
                        : String.format("%.1f 秒", (times.get(times.size() - 1) - times.get(0)) / 1000.0 / (times.size() - 1));
                Map<String, Object> row = jdbcTemplate.queryForMap(
                        "select status, attempt_count, last_error from notification_outbox where notification_id = ?", id);
                report(NAME, String.format("任务 %s：被投递并退回 %d 次，平均间隔 %s，状态=%s，attempt_count=%s，last_error=%s",
                        id.substring(0, 8), times.size(), interval,
                        row.get("status"), row.get("attempt_count"), row.get("last_error")));
            }
            report(NAME, "---- 汇总 ----");
            report(NAME, seconds + " 秒内 " + taskCount + " 个任务共被投递 " + total + " 次，平均每个任务 "
                    + String.format("%.1f", total / (double) taskCount) + " 次；没有次数上限，状态一直是 PENDING");

            jdbcTemplate.update("delete from notification_outbox where activity_id = ?", f.activityId());
        } finally {
            rabbitAdmin.declareBinding(notificationBinding);
            onReturned(null);
        }
    }
}
