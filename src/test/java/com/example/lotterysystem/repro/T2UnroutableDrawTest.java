package com.example.lotterysystem.repro;

import com.example.lotterysystem.common.config.DirectRabbitConfig;
import com.example.lotterysystem.service.DrawPrizeService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.concurrent.atomic.AtomicInteger;

import static com.example.lotterysystem.common.config.DirectRabbitConfig.DLX_QUEUE_NAME;
import static com.example.lotterysystem.common.config.DirectRabbitConfig.QUEUE_NAME;

/**
 * T2 开奖入口：删掉开奖交换机到开奖队列的绑定，让消息路由不到，再调用开奖。
 * 控制器只要 service 没抛异常就返回 success(true)，所以看 service 是否抛异常即可。
 * 测试结束后恢复绑定。
 *
 * <p>运行：./mvnw test -Prepro -Dtest=T2UnroutableDrawTest -Drounds=3</p>
 */
class T2UnroutableDrawTest extends ReproSupport {

    private static final String NAME = "T2";

    @Autowired
    private DrawPrizeService drawPrizeService;
    @Autowired
    @Qualifier("bindingDirect")
    private Binding drawBinding;

    @Test
    void drawWhenMessageCannotBeRouted() {
        int rounds = Integer.getInteger("rounds", 3);
        cleanupReproData();
        startReport(NAME, "rounds=" + rounds + " 已删除 DirectExchange -> DirectQueue 的绑定");

        AtomicInteger returned = new AtomicInteger();
        onReturned(r -> {
            if (DirectRabbitConfig.EXCHANGE_NAME.equals(r.getExchange())) {
                returned.incrementAndGet();
            }
        });
        rabbitAdmin.removeBinding(drawBinding);
        int apiSuccess = 0;
        int lost = 0;
        try {
            for (int i = 1; i <= rounds; i++) {
                DrawFixture f = createDrawFixture(NAME);
                int queueBefore = queueCount(QUEUE_NAME);
                int deadBefore = queueCount(DLX_QUEUE_NAME);
                int returnedBefore = returned.get();

                String result;
                try {
                    drawPrizeService.drawPrize(f.param());
                    result = "service 正常返回，接口会返回成功";
                    apiSuccess++;
                } catch (Exception e) {
                    result = "service 抛出异常，接口返回失败：" + e;
                }
                sleep(3000);

                int newInQueue = queueCount(QUEUE_NAME) - queueBefore;
                int newDead = queueCount(DLX_QUEUE_NAME) - deadBefore;
                int newReturned = returned.get() - returnedBefore;
                if (newInQueue == 0 && newDead == 0) {
                    lost++;
                }
                report(NAME, String.format("第 %d 轮：%s；被 Broker 退回=%d，进入开奖队列=%d，进入死信=%d，奖品=%s，中奖记录=%d",
                        i, result, newReturned, newInQueue, newDead, prizeStatus(f), recordCount(f)));
            }
        } finally {
            rabbitAdmin.declareBinding(drawBinding);
            onReturned(null);
        }
        report(NAME, "---- 汇总 ----");
        report(NAME, "总轮数=" + rounds + "，接口返回成功=" + apiSuccess + "，消息既不在队列也不在死信（丢失）=" + lost);
    }
}
