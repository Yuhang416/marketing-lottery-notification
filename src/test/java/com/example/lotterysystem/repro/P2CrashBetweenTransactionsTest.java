package com.example.lotterysystem.repro;

import com.example.lotterysystem.service.DrawPrizeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static com.example.lotterysystem.common.config.DirectRabbitConfig.QUEUE_NAME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * P2 两个事务之间进程退出。分两次运行，由 scripts/repro-p2.sh 串起来：
 * <ol>
 *   <li>crashAfterFirstTransaction：事务一（状态流转）正常提交，在执行事务二
 *       saveWinnerRecords 时直接 halt(1)，模拟进程被杀，不走任何异常和补偿；</li>
 *   <li>recoverAfterRestart：重新启动，未确认的开奖消息会被重新投递，统计最终结果。</li>
 * </ol>
 */
class P2CrashBetweenTransactionsTest extends ReproSupport {

    private static final String NAME = "P2";
    private static final Path FIXTURE_FILE = REPORT_DIR.resolve("p2-fixture.properties");

    @SpyBean
    private DrawPrizeService drawPrizeService;

    @Test
    void crashAfterFirstTransaction() throws IOException {
        cleanupReproData();
        DrawFixture f = createDrawFixture(NAME);
        saveFixture(f);
        startReport(NAME, "第一次运行：事务一提交后、写中奖记录前进程退出");

        doAnswer(inv -> {
            report(NAME, "事务一已提交（奖品=" + prizeStatus(f) + "，活动=" + activityStatus(f)
                    + "），进程现在退出");
            Runtime.getRuntime().halt(1);
            return null;
        }).when(drawPrizeService).saveWinnerRecords(any());

        drawPrizeService.drawPrize(f.param());
        sleep(30_000);
        report(NAME, "30 秒内进程没有退出，故障注入没有生效");
    }

    @Test
    void recoverAfterRestart() throws IOException {
        DrawFixture f = loadFixture();
        report(NAME, "第二次运行：进程重启，等待开奖消息重新投递并处理");
        awaitStable(() -> List.of(prizeStatus(f), activityStatus(f), recordCount(f), outboxCount(f),
                queueCount(QUEUE_NAME)), 8000, 60_000);
        report(NAME, String.format("重启后：奖品=%s，活动=%s，中奖记录=%d，通知任务=%d，开奖队列剩余=%d",
                prizeStatus(f), activityStatus(f), recordCount(f), outboxCount(f), queueCount(QUEUE_NAME)));
    }

    private void saveFixture(DrawFixture f) throws IOException {
        Files.createDirectories(REPORT_DIR);
        Properties p = new Properties();
        p.setProperty("activityId", String.valueOf(f.activityId()));
        p.setProperty("prizeId", String.valueOf(f.prizeId()));
        p.setProperty("userId", String.valueOf(f.userId()));
        try (Writer w = Files.newBufferedWriter(FIXTURE_FILE)) {
            p.store(w, "P2 fixture");
        }
    }

    private DrawFixture loadFixture() throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FIXTURE_FILE)) {
            p.load(r);
        }
        return new DrawFixture(Long.valueOf(p.getProperty("activityId")), Long.valueOf(p.getProperty("prizeId")),
                Long.valueOf(p.getProperty("userId")), null, null, null);
    }
}
