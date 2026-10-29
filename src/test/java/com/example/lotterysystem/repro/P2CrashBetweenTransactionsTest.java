package com.example.lotterysystem.repro;

import com.example.lotterysystem.service.DrawPrizeService;
import com.example.lotterysystem.service.enums.ActivityPrizeStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import static com.example.lotterysystem.common.config.DirectRabbitConfig.QUEUE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * P2 开奖进行到一半时进程退出。分两次运行，由 scripts/repro-p2.sh 串起来：
 * <ol>
 *   <li>crashAfterFirstTransaction：状态流转执行完，在调用 saveWinnerRecords 时直接 halt(1)，
 *       模拟进程被杀，不走任何异常和补偿。修复前状态流转是单独的事务，此时已经提交；
 *       修复后两者在同一个事务里，进程退出后整个事务回滚；</li>
 *   <li>recoverAfterRestart：重新启动，未确认的开奖消息会被重新投递，统计最终结果。
 *       步骤 2 修复后加了断言：重启后必须正常完成开奖。</li>
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
        startReport(NAME, "第一次运行：状态流转之后、写中奖记录之前进程退出");

        doAnswer(inv -> {
            // 在另一个线程里查，用的是另一个数据库连接，看到的是已经提交的数据；
            // 在当前线程查会复用事务里的连接，看到的是还没提交的数据
            String committed = CompletableFuture.supplyAsync(
                    () -> "奖品=" + prizeStatus(f) + "，活动=" + activityStatus(f)).join();
            report(NAME, "状态流转已执行、写中奖记录前进程退出，此时其他连接看到的已提交状态：" + committed);
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

        // 步骤 2 的验收条件：重启后开奖正常完成
        assertEquals(ActivityPrizeStatusEnum.COMPLETED.name(), prizeStatus(f), "奖品状态");
        assertEquals(1, recordCount(f), "中奖记录条数");
        assertEquals(2, outboxCount(f), "通知任务条数");
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
