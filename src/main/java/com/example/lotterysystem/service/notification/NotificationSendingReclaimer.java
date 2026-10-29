package com.example.lotterysystem.service.notification;

import com.example.lotterysystem.dao.mapper.NotificationOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 回收发送租约到期的通知任务。
 *
 * <p>消费者抢到发送权（SENDING）后崩溃，消息重投时会因为抢不到发送权被跳过，任务就一直停在 SENDING。
 * 定时把租约到期的 SENDING 改回 PENDING，由投递器重新投递。条件更新，多实例同时执行也没问题。</p>
 */
@Slf4j
@Component
public class NotificationSendingReclaimer {

    @Autowired
    private NotificationOutboxMapper notificationOutboxMapper;

    @Scheduled(fixedDelayString = "${notification.sending.reclaim-interval-ms:60000}")
    public int reclaim() {
        int reclaimed = notificationOutboxMapper.reclaimExpiredSending(new Date());
        if (reclaimed > 0) {
            log.warn("回收了 {} 个发送租约已到期的通知任务，将重新投递；这些通知可能已经发过一次", reclaimed);
        }
        return reclaimed;
    }
}
