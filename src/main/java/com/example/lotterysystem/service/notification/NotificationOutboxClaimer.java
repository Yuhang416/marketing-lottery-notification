package com.example.lotterysystem.service.notification;

import com.example.lotterysystem.dao.dataobject.NotificationOutboxDO;
import com.example.lotterysystem.dao.mapper.NotificationOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 领取到期的通知任务，供 {@link NotificationOutboxPublisher} 投递。
 *
 * <p>用 next_retry_time 当租约：领走一个任务就把它的下次时间往后推，别的实例在这之前扫描不到它；
 * 投递失败不用做任何事，租约到期后任务会被重新领取，间隔按退避序列逐次变长。
 * 实例领取后、投递前宕机也一样，租约到期后由别的实例接手。</p>
 *
 * <p>单独放一个类：在投递器内部调用自己的 @Transactional 方法不经过 Spring 代理，事务不会生效。</p>
 */
@Slf4j
@Component
public class NotificationOutboxClaimer {

    private static final int LAST_ERROR_MAX_LENGTH = 500;

    @Autowired
    private NotificationOutboxMapper notificationOutboxMapper;
    @Value("${notification.outbox.max-attempts:8}")
    private int maxAttempts;
    @Value("${notification.outbox.backoff-seconds:10,30,60,120,300}")
    private int[] backoffSeconds;
    @Value("${notification.outbox.backoff-jitter-ratio:0.2}")
    private double jitterRatio;

    /**
     * 在一个短事务里领取最多 batchSize 个到期任务，提交后返回需要投递的任务。
     * 已经投递了 maxAttempts 次的任务改成 FAILED 并告警，不再返回。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<NotificationOutboxDO> claim(int batchSize) {
        Date now = new Date();
        List<NotificationOutboxDO> due = notificationOutboxMapper.selectClaimableForUpdate(now, batchSize);
        List<NotificationOutboxDO> toPublish = new ArrayList<>();
        for (NotificationOutboxDO task : due) {
            int attempts = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
            if (attempts >= maxAttempts) {
                String lastError = truncate("投递 " + attempts + " 次仍未成功，最后一次错误：" + task.getLastError());
                notificationOutboxMapper.markFailed(task.getId(), lastError);
                log.error("通知任务重试达到上限，已停止投递，需要人工处理：notificationId={}, attempts={}, lastError={}",
                        task.getNotificationId(), attempts, task.getLastError());
                continue;
            }
            notificationOutboxMapper.extendLease(task.getId(),
                    new Date(now.getTime() + leaseSeconds(attempts) * 1000L));
            task.setAttemptCount(attempts + 1);
            toPublish.add(task);
        }
        return toPublish;
    }

    /** 第 attempts + 1 次投递的租约：按退避序列取值，超过序列长度用最后一个值，再加随机抖动。 */
    private long leaseSeconds(int attempts) {
        int base = backoffSeconds[Math.min(attempts, backoffSeconds.length - 1)];
        double jitter = base * jitterRatio * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
        return Math.max(1, Math.round(base + jitter));
    }

    static String truncate(String text) {
        if (text == null || text.length() <= LAST_ERROR_MAX_LENGTH) {
            return text;
        }
        return text.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
