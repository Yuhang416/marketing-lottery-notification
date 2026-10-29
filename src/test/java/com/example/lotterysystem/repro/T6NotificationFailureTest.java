package com.example.lotterysystem.repro;

import com.example.lotterysystem.common.utils.SMSUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.List;

import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_QUEUE_NAME;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * T6 通知失败耗尽：mock 短信工具固定抛异常。任务走完整链路（PENDING → 投递 → 消费），
 * 统计实际尝试发送了几次、消息最后去了哪里、任务最后停在什么状态。
 *
 * <p>运行：./mvnw test -Prepro -Dtest=T6NotificationFailureTest -Drounds=3</p>
 */
class T6NotificationFailureTest extends ReproSupport {

    private static final String NAME = "T6";

    @SpyBean
    private SMSUtil smsUtil;

    @BeforeEach
    void alwaysFail() {
        doThrow(new IllegalStateException("复现测试：模拟短信发送失败"))
                .when(smsUtil).sendMessage(anyString(), anyString(), anyString());
    }

    @AfterEach
    void resetSpy() {
        Mockito.reset(smsUtil);
    }

    @Test
    void notificationAlwaysFails() {
        int rounds = Integer.getInteger("rounds", 3);
        long observeMs = Long.getLong("observeMs", 15_000);
        cleanupReproData();
        startReport(NAME, "rounds=" + rounds + " 短信发送固定抛异常，通知队列没有配置死信");

        for (int i = 1; i <= rounds; i++) {
            DrawFixture f = createDrawFixture(NAME);
            insertWinningRecord(f);
            String notificationId = insertTask(f, f.userId(), "SMS", "PENDING");

            long start = System.currentTimeMillis();
            awaitStable(() -> List.of(taskStatus(notificationId), attempts(f.phone())), 6000, 60_000);
            int attempts = attempts(f.phone());
            String status = taskStatus(notificationId);

            // 再观察一段时间，确认之后没有任何组件再处理这个任务
            sleep(observeMs);
            report(NAME, String.format("第 %d 轮：尝试发送 %d 次（从写入任务到状态稳定约 %.1f 秒，含 6 秒稳定判断），稳定后状态=%s；再等 %d 秒：状态=%s，尝试次数=%d，通知队列剩余消息=%d",
                    i, attempts, (System.currentTimeMillis() - start - observeMs) / 1000.0, status,
                    observeMs / 1000, taskStatus(notificationId), attempts(f.phone()), queueCount(NOTIFICATION_QUEUE_NAME)));
        }
    }

    private int attempts(String phone) {
        return (int) Mockito.mockingDetails(smsUtil).getInvocations().stream()
                .filter(inv -> "sendMessage".equals(inv.getMethod().getName()))
                .filter(inv -> phone.equals(inv.getArguments()[1]))
                .count();
    }
}
