package com.example.lotterysystem.repro;

import com.example.lotterysystem.dao.dataobject.NotificationOutboxDO;
import com.example.lotterysystem.service.notification.NotificationOutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_QUEUE_NAME;

/**
 * T3 双实例投递。由 scripts/repro-t3.sh 按角色分别运行：
 * <ul>
 *   <li>seed：写入 N 条 PENDING 任务；</li>
 *   <li>scan：两个 JVM 同时运行，到约定时间点（fireAt）各执行一次投递扫描；</li>
 *   <li>count：读空通知队列，统计每个任务被投递了几次。</li>
 * </ul>
 * repro-t3 配置关掉了所有消费者和自动扫描，消息会留在通知队列里等 count 来数。
 */
@ActiveProfiles({"local", "repro", "repro-t3"})
class T3DuplicatePublishTest extends ReproSupport {

    private static final String NAME = "T3";
    private static final Path MARKER_FILE = REPORT_DIR.resolve("t3-marker.properties");

    @Autowired
    private NotificationOutboxPublisher publisher;

    @Test
    void run() throws IOException {
        switch (System.getProperty("role", "")) {
            case "seed" -> seed();
            case "scan" -> scan();
            case "count" -> count();
            default -> report(NAME, "请用 -Drole=seed|scan|count 指定角色，或者直接运行 scripts/repro-t3.sh");
        }
    }

    private void seed() throws IOException {
        int taskCount = Integer.getInteger("tasks", 1000);
        cleanupReproData();
        DrawFixture f = createDrawFixture(NAME);
        List<NotificationOutboxDO> batch = new ArrayList<>();
        for (long winnerId = 1; winnerId <= taskCount; winnerId++) {
            NotificationOutboxDO task = new NotificationOutboxDO();
            task.setNotificationId(UUID.randomUUID().toString());
            task.setActivityId(f.activityId());
            task.setPrizeId(f.prizeId());
            task.setWinnerId(winnerId);
            task.setChannel("SMS");
            task.setStatus("PENDING");
            task.setAttemptCount(0);
            task.setNextRetryTime(new Date());
            batch.add(task);
            if (batch.size() == 500) {
                notificationOutboxMapper.batchInsert(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            notificationOutboxMapper.batchInsert(batch);
        }
        Files.createDirectories(REPORT_DIR);
        Properties p = new Properties();
        p.setProperty("activityId", String.valueOf(f.activityId()));
        p.setProperty("tasks", String.valueOf(taskCount));
        try (Writer w = Files.newBufferedWriter(MARKER_FILE)) {
            p.store(w, "T3 marker");
        }
        int otherPending = jdbcTemplate.queryForObject(
                "select count(1) from notification_outbox where status = 'PENDING' and activity_id <> ?",
                Integer.class, f.activityId());
        report(NAME, "seed：写入 PENDING 任务 " + taskCount + " 条；库里其他 PENDING 任务 " + otherPending + " 条");
    }

    private void scan() {
        long fireAt = Long.getLong("fireAt", System.currentTimeMillis());
        String instance = System.getProperty("instance", "?");
        long waitMs = fireAt - System.currentTimeMillis();
        if (waitMs > 0) {
            sleep(waitMs);
        } else {
            report(NAME, "实例 " + instance + " 启动晚了 " + (-waitMs) + "ms，错过约定时间，立即扫描");
        }
        long begin = System.currentTimeMillis();
        publisher.publishPendingTasks();
        long end = System.currentTimeMillis();
        // 等 Confirm 回来，把任务改成 PUBLISHED
        sleep(10_000);
        report(NAME, String.format("实例 %s：约定时间后 %dms 开始扫描，扫描加发送耗时 %dms",
                instance, begin - fireAt, end - begin));
    }

    private void count() throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(MARKER_FILE)) {
            p.load(r);
        }
        long activityId = Long.parseLong(p.getProperty("activityId"));
        int taskCount = Integer.parseInt(p.getProperty("tasks"));
        Set<String> ours = new HashSet<>(jdbcTemplate.queryForList(
                "select notification_id from notification_outbox where activity_id = ?", String.class, activityId));

        Map<String, Integer> deliveries = new HashMap<>();
        int total = 0;
        int others = 0;
        Object message;
        while ((message = rabbitTemplate.receiveAndConvert(NOTIFICATION_QUEUE_NAME)) != null) {
            total++;
            String id = String.valueOf(((Map<?, ?>) message).get("notificationId"));
            if (ours.contains(id)) {
                deliveries.merge(id, 1, Integer::sum);
            } else {
                others++;
            }
        }
        int ourMessages = deliveries.values().stream().mapToInt(Integer::intValue).sum();
        Map<Integer, Integer> distribution = new TreeMap<>();
        for (String id : ours) {
            distribution.merge(deliveries.getOrDefault(id, 0), 1, Integer::sum);
        }
        Map<String, Integer> statuses = new TreeMap<>();
        jdbcTemplate.query("select status, count(1) c from notification_outbox where activity_id = ? group by status",
                rs -> {
                    statuses.put(rs.getString("status"), rs.getInt("c"));
                }, activityId);

        report(NAME, "---- 汇总 ----");
        report(NAME, String.format("任务 %d 条，通知队列收到本次任务的消息 %d 条（队列里其他消息 %d 条），重复投递 %d 条",
                taskCount, ourMessages, others, ourMessages - deliveries.size()));
        report(NAME, "{投递次数=任务数} " + distribution + "，任务状态 " + statuses);
        cleanupReproData();
    }
}
