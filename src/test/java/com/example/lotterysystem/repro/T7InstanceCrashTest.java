package com.example.lotterysystem.repro;

import com.example.lotterysystem.dao.dataobject.NotificationOutboxDO;
import com.example.lotterysystem.service.notification.NotificationOutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import java.util.concurrent.CompletableFuture;

import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_QUEUE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * T7 实例领取任务后宕机。由 scripts/repro-t7.sh 按角色分别运行：
 * <ul>
 *   <li>seed：写入 N 条 PENDING 任务；</li>
 *   <li>crash：领取任务（事务已提交、租约已设置）后，在发出第一条消息前 halt(1)；</li>
 *   <li>recover：另一个进程正常运行投递器，等租约到期接手，然后读空通知队列统计投递次数。</li>
 * </ul>
 * 用 repro-t3 配置关掉消费者，消息留在通知队列里等着数；recover 时用 -D 打开自动扫描。
 */
@ActiveProfiles({"local", "repro", "repro-t3"})
class T7InstanceCrashTest extends ReproSupport {

    private static final String NAME = "T7";
    private static final Path MARKER_FILE = REPORT_DIR.resolve("t7-marker.properties");

    @Autowired
    private NotificationOutboxPublisher publisher;
    @SpyBean
    private RabbitTemplate spiedRabbitTemplate;

    @Test
    void run() throws IOException {
        switch (System.getProperty("role", "")) {
            case "seed" -> seed();
            case "crash" -> crash();
            case "recover" -> recover();
            default -> report(NAME, "请用 -Drole=seed|crash|recover 指定角色，或者直接运行 scripts/repro-t7.sh");
        }
    }

    private void seed() throws IOException {
        int taskCount = Integer.getInteger("tasks", 20);
        cleanupReproData();
        DrawFixture f = createDrawFixture(NAME);
        List<NotificationOutboxDO> tasks = new ArrayList<>();
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
            tasks.add(task);
        }
        notificationOutboxMapper.batchInsert(tasks);
        Files.createDirectories(REPORT_DIR);
        Properties p = new Properties();
        p.setProperty("activityId", String.valueOf(f.activityId()));
        p.setProperty("tasks", String.valueOf(taskCount));
        try (Writer w = Files.newBufferedWriter(MARKER_FILE)) {
            p.store(w, "T7 marker");
        }
        startReport(NAME, "seed：写入 PENDING 任务 " + taskCount + " 条");
    }

    private void crash() throws IOException {
        long activityId = loadActivityId();
        // 领取事务提交后，投递器发第一条通知消息时进程直接退出
        doAnswer(inv -> {
            String claimed = CompletableFuture.supplyAsync(() -> leaseSummary(activityId)).join();
            report(NAME, "crash：已领取任务、还没发出任何消息，进程现在退出。已提交的状态：" + claimed);
            Runtime.getRuntime().halt(1);
            return null;
        }).when(spiedRabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        publisher.publishPendingTasks();
        report(NAME, "crash：没有领到任务，故障注入没有生效");
    }

    private void recover() throws IOException {
        long activityId = loadActivityId();
        int taskCount = Integer.parseInt(loadMarker().getProperty("tasks"));
        report(NAME, "recover：新进程启动，" + leaseSummary(activityId));

        long start = System.currentTimeMillis();
        long deadline = start + 90_000;
        while (System.currentTimeMillis() < deadline && countByStatus(activityId, "PUBLISHED") < taskCount) {
            sleep(500);
        }
        long waited = System.currentTimeMillis() - start;

        Set<String> ours = new HashSet<>(jdbcTemplate.queryForList(
                "select notification_id from notification_outbox where activity_id = ?", String.class, activityId));
        Map<String, Integer> deliveries = new HashMap<>();
        Object message;
        while ((message = rabbitTemplate.receiveAndConvert(NOTIFICATION_QUEUE_NAME)) != null) {
            String id = String.valueOf(((Map<?, ?>) message).get("notificationId"));
            if (ours.contains(id)) {
                deliveries.merge(id, 1, Integer::sum);
            }
        }
        int messages = deliveries.values().stream().mapToInt(Integer::intValue).sum();
        Map<Integer, Integer> distribution = new TreeMap<>();
        for (String id : ours) {
            distribution.merge(deliveries.getOrDefault(id, 0), 1, Integer::sum);
        }
        report(NAME, "---- 汇总 ----");
        report(NAME, String.format("新进程启动 %.1f 秒后全部投递完成；任务 %d 条，收到消息 %d 条，重复 %d 条；{投递次数=任务数} %s，%s",
                waited / 1000.0, taskCount, messages, messages - deliveries.size(), distribution, leaseSummary(activityId)));
        cleanupReproData();

        // 验收条件：宕机实例领走的任务全部被接手，而且每个只投递一次
        assertEquals(taskCount, deliveries.size(), "被接手投递的任务数");
        assertEquals(0, messages - deliveries.size(), "重复投递条数");
    }

    private String leaseSummary(long activityId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select sum(status = 'PENDING') pending, sum(status = 'PUBLISHED') published,"
                        + " min(attempt_count) min_attempts, max(attempt_count) max_attempts, min(next_retry_time) earliest_lease_end"
                        + " from notification_outbox where activity_id = ?", activityId);
        return String.format("PENDING=%s，PUBLISHED=%s，attempt_count=%s~%s，最早的租约到期时间=%s（应用时间，现在是 %tT）",
                row.get("pending"), row.get("published"), row.get("min_attempts"), row.get("max_attempts"),
                row.get("earliest_lease_end"), new Date());
    }

    private int countByStatus(long activityId, String status) {
        return jdbcTemplate.queryForObject(
                "select count(1) from notification_outbox where activity_id = ? and status = ?",
                Integer.class, activityId, status);
    }

    private long loadActivityId() throws IOException {
        return Long.parseLong(loadMarker().getProperty("activityId"));
    }

    private Properties loadMarker() throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(MARKER_FILE)) {
            p.load(r);
        }
        return p;
    }
}
