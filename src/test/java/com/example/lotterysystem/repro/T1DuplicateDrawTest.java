package com.example.lotterysystem.repro;

import com.example.lotterysystem.controller.param.DrawPrizeParam;
import com.example.lotterysystem.service.DrawPrizeService;
import com.example.lotterysystem.service.enums.ActivityPrizeStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.lotterysystem.common.config.DirectRabbitConfig.DLX_QUEUE_NAME;
import static com.example.lotterysystem.common.config.DirectRabbitConfig.QUEUE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T1 重复开奖：同一个奖品的开奖消息连续发两条，开奖消费者并发 2、prefetch 1，
 * 两条消息会分给两个消费者同时处理。每轮结束后统计中奖记录、奖品状态、通知任务。
 * 步骤 2 修复后加了断言：误撤销和重复都必须是 0。
 *
 * <p>运行：./mvnw test -Prepro -Dtest=T1DuplicateDrawTest -Drounds=100</p>
 */
class T1DuplicateDrawTest extends ReproSupport {

    private static final String NAME = "T1";

    @Autowired
    private DrawPrizeService drawPrizeService;

    @Test
    void sameDrawMessageTwice() {
        int rounds = Integer.getInteger("rounds", 100);
        long stableMs = Long.getLong("stableMs", 6000);
        cleanupReproData();
        startReport(NAME, "rounds=" + rounds + " stableMs=" + stableMs
                + " 开奖消费者并发=2 prefetch=1 每轮中奖人数=1");

        Map<String, Integer> outcomes = new LinkedHashMap<>();
        outcomes.put("正常", 0);
        outcomes.put("误撤销", 0);
        outcomes.put("重复", 0);
        outcomes.put("其他", 0);
        int deadLetterRounds = 0;
        int notifiedButRevoked = 0;

        for (int i = 1; i <= rounds; i++) {
            DrawFixture f = createDrawFixture(NAME);
            int deadLettersBefore = queueCount(DLX_QUEUE_NAME);

            DrawPrizeParam param = f.param();
            drawPrizeService.drawPrize(param);
            drawPrizeService.drawPrize(param);

            awaitStable(() -> List.of(prizeStatus(f), activityStatus(f), recordCount(f),
                    outboxCount(f), queueCount(QUEUE_NAME), queueCount(DLX_QUEUE_NAME)),
                    stableMs, 60_000);

            int records = recordCount(f);
            int tasks = outboxCount(f);
            int newDeadLetters = queueCount(DLX_QUEUE_NAME) - deadLettersBefore;
            String prize = prizeStatus(f);
            Map<String, Integer> taskStatus = outboxStatusCounts(f);

            String outcome;
            if (records == 1 && tasks == 2 && ActivityPrizeStatusEnum.COMPLETED.name().equals(prize)) {
                outcome = "正常";
            } else if (records == 0 && tasks > 0) {
                // 通知任务只会在开奖成功时写入：有任务、没有中奖记录，说明成功的开奖被撤销了
                outcome = "误撤销";
                if (taskStatus.containsKey("DONE")) {
                    notifiedButRevoked++;
                }
            } else if (records > 1 || tasks > 2) {
                outcome = "重复";
            } else {
                outcome = "其他";
            }
            outcomes.merge(outcome, 1, Integer::sum);
            if (newDeadLetters > 0) {
                deadLetterRounds++;
            }

            report(NAME, String.format("第 %d 轮：%s，奖品=%s，活动=%s，中奖记录=%d，通知任务=%d %s，新增死信=%d",
                    i, outcome, prize, activityStatus(f), records, tasks, taskStatus, newDeadLetters));
        }

        report(NAME, "---- 汇总 ----");
        report(NAME, "总轮数=" + rounds + "，" + outcomes);
        report(NAME, "有消息进入开奖死信队列的轮数=" + deadLetterRounds);
        report(NAME, "误撤销且通知已经发出（任务 DONE）的轮数=" + notifiedButRevoked);

        // 步骤 2 的验收条件：每一轮都只开奖一次，不误撤销、不重复
        assertEquals(0, outcomes.get("误撤销"), "误撤销轮数");
        assertEquals(0, outcomes.get("重复"), "重复轮数");
        assertEquals(rounds, outcomes.get("正常"), "正常轮数");
    }
}
