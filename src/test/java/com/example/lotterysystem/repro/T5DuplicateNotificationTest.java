package com.example.lotterysystem.repro;

import com.example.lotterysystem.common.utils.SMSUtil;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_EXCHANGE_NAME;
import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_ROUTING_KEY;

/**
 * T5 重复通知：中奖记录和通知任务（PUBLISHED）先写好，同一条通知消息连续发两条，
 * 通知消费者并发 2、prefetch 1。用 SpyBean 统计 mock 短信工具对这个中奖人实际发送的次数。
 *
 * <p>运行：./mvnw test -Prepro -Dtest=T5DuplicateNotificationTest -Drounds=100</p>
 */
class T5DuplicateNotificationTest extends ReproSupport {

    private static final String NAME = "T5";

    @SpyBean
    private SMSUtil smsUtil;

    @Test
    void sameNotificationMessageTwice() {
        int rounds = Integer.getInteger("rounds", 100);
        cleanupReproData();
        startReport(NAME, "rounds=" + rounds + " 通知消费者并发=2 prefetch=1 渠道=SMS");

        Map<Integer, Integer> sendsDistribution = new TreeMap<>();
        for (int i = 1; i <= rounds; i++) {
            DrawFixture f = createDrawFixture(NAME);
            insertWinningRecord(f);
            String notificationId = insertTask(f, f.userId(), "SMS", "PUBLISHED");

            Map<String, String> message = Map.of("notificationId", notificationId, "channel", "SMS");
            rabbitTemplate.convertAndSend(NOTIFICATION_EXCHANGE_NAME, NOTIFICATION_ROUTING_KEY, message);
            rabbitTemplate.convertAndSend(NOTIFICATION_EXCHANGE_NAME, NOTIFICATION_ROUTING_KEY, message);

            awaitStable(() -> List.of(taskStatus(notificationId), sendCount(f.phone())), 2000, 30_000);
            int sends = sendCount(f.phone());
            sendsDistribution.merge(sends, 1, Integer::sum);
            report(NAME, String.format("第 %d 轮：实际发送 %d 次，任务状态=%s", i, sends, taskStatus(notificationId)));
        }
        report(NAME, "---- 汇总 ----");
        report(NAME, "总轮数=" + rounds + "，{发送次数=轮数} " + sendsDistribution);
    }

    private int sendCount(String phone) {
        return (int) Mockito.mockingDetails(smsUtil).getInvocations().stream()
                .filter(inv -> "sendMessage".equals(inv.getMethod().getName()))
                .filter(inv -> phone.equals(inv.getArguments()[1]))
                .count();
    }
}
