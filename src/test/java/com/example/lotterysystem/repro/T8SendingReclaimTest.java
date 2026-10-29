package com.example.lotterysystem.repro;

import com.example.lotterysystem.common.utils.SMSUtil;
import com.example.lotterysystem.service.notification.NotificationSendingReclaimer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8 消费者抢到发送权后崩溃：任务停在 SENDING，消息重投时会因为抢不到发送权被跳过。
 * 这里直接造一个发送租约已经过期的 SENDING 任务，模拟崩溃后的状态，
 * 运行回收后应变回 PENDING，随后被投递器重新投递、正常发出。
 *
 * <p>运行：./mvnw test -Prepro -Dtest=T8SendingReclaimTest</p>
 */
class T8SendingReclaimTest extends ReproSupport {

    private static final String NAME = "T8";

    @SpyBean
    private SMSUtil smsUtil;
    @Autowired
    private NotificationSendingReclaimer reclaimer;

    @Test
    void expiredSendingTaskIsReclaimed() {
        cleanupReproData();
        startReport(NAME, "发送租约已过期的 SENDING 任务，运行回收");
        DrawFixture f = createDrawFixture(NAME);
        insertWinningRecord(f);
        String notificationId = insertTask(f, f.userId(), "SMS", "SENDING");
        jdbcTemplate.update("update notification_outbox set next_retry_time = ? where notification_id = ?",
                new Date(System.currentTimeMillis() - 1000), notificationId);

        int reclaimed = reclaimer.reclaim();
        String afterReclaim = taskStatus(notificationId);

        // 稳定判断要比投递器的扫描间隔（3 秒）长，否则会在投递器扫到它之前就提前结束
        long start = System.currentTimeMillis();
        awaitStable(() -> List.of(taskStatus(notificationId), sendCount(f.phone())), 8000, 30_000);
        report(NAME, String.format("回收了 %d 个任务，回收后状态=%s；%.1f 秒后状态=%s，发送次数=%d",
                reclaimed, afterReclaim, (System.currentTimeMillis() - start) / 1000.0,
                taskStatus(notificationId), sendCount(f.phone())));

        assertTrue(reclaimed >= 1, "回收的任务数");
        assertEquals("PENDING", afterReclaim, "回收后的状态");
        assertEquals("DONE", taskStatus(notificationId), "重新投递后的状态");
        assertEquals(1, sendCount(f.phone()), "发送次数");
    }

    private int sendCount(String phone) {
        return (int) Mockito.mockingDetails(smsUtil).getInvocations().stream()
                .filter(inv -> "sendMessage".equals(inv.getMethod().getName()))
                .filter(inv -> inv.getArguments().length == 4)
                .filter(inv -> phone.equals(inv.getArguments()[1]))
                .count();
    }
}
