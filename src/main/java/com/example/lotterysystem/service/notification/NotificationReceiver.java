package com.example.lotterysystem.service.notification;

import cn.hutool.core.date.DateUtil;
import com.example.lotterysystem.common.utils.JacksonUtil;
import com.example.lotterysystem.common.utils.MailUtil;
import com.example.lotterysystem.common.utils.SMSUtil;
import com.example.lotterysystem.dao.dataobject.NotificationOutboxDO;
import com.example.lotterysystem.dao.dataobject.WinningRecordDO;
import com.example.lotterysystem.dao.mapper.NotificationOutboxMapper;
import com.example.lotterysystem.dao.mapper.WinningRecordMapper;
import com.example.lotterysystem.service.enums.ActivityPrizeTiersEnum;
import com.example.lotterysystem.service.enums.NotificationChannelEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_QUEUE_NAME;

/**
 * 通知专线的消费者：真正调用短信/邮件工具，并在成功后结束 Outbox 任务。
 *
 * <p>同一个任务的消息可能被多个消费者同时处理（重复投递、多实例），所以发送前先用条件更新
 * 抢占发送权，只有抢到的消费者才发送。发送端只能保证至少发一次：发送成功后、结案前进程崩溃，
 * 任务会被回收重发，最终去重靠下游按 notificationId 处理。</p>
 */
@Slf4j
@Component
@RabbitListener(queues = NOTIFICATION_QUEUE_NAME)
public class NotificationReceiver {

    @Autowired
    private NotificationOutboxMapper outboxMapper;
    @Autowired
    private WinningRecordMapper winningRecordMapper;
    @Autowired
    private SMSUtil smsUtil;
    @Autowired
    private MailUtil mailUtil;
    @Value("${notification.sending.lease-seconds:300}")
    private long sendingLeaseSeconds;

    @RabbitHandler
    public void process(Map<String, String> message) {
        String notificationId = message.get("notificationId");
        if (!StringUtils.hasText(notificationId)) {
            return;
        }

        NotificationOutboxDO task = outboxMapper.selectByNotificationId(notificationId);
        if (task == null) {
            return;
        }

        // 1. 抢占发送权。影响 0 行：别的消费者正在发，或者已经 DONE / CANCELLED，直接结束。
        //    允许 PENDING：消息可能先到消费者，随后 Confirm 回调才把任务改成 PUBLISHED。
        Date leaseUntil = new Date(System.currentTimeMillis() + sendingLeaseSeconds * 1000);
        if (outboxMapper.claimForSending(notificationId, leaseUntil) == 0) {
            log.info("通知任务已被处理或正在处理，忽略本条消息，notificationId={}", notificationId);
            return;
        }

        try {
            // 2. Claim-Check：根据任务中的业务 ID 回查中奖详情。
            WinningRecordDO record = winningRecordMapper.selectByActivityPrizeAndWinner(
                    task.getActivityId(), task.getPrizeId(), task.getWinnerId());
            if (record == null) {
                // 中奖记录已不存在，不应该再发通知。
                outboxMapper.markCancelled(notificationId);
                return;
            }

            // 3. 真实发送通知。发送失败会抛异常，RabbitMQ 会按现有配置重试该消息。
            sendNotice(notificationId, task.getChannel(), record);
        } catch (RuntimeException e) {
            // 交还发送权再抛出：监听器会在进程内重试，状态还是 SENDING 的话就再也抢不到了。
            // 重试耗尽后消息进入通知死信队列。
            outboxMapper.releaseSending(notificationId);
            throw e;
        }

        // 4. 终态闭环：只有工具类明确发送成功才更新为 DONE。
        outboxMapper.markDone(notificationId);
        log.info("通知发送完成，notificationId={}", notificationId);
    }

    private void sendNotice(String notificationId, String channel, WinningRecordDO record) {
        if (NotificationChannelEnum.SMS.name().equals(channel)) {
            sendSms(notificationId, record);
            return;
        }
        if (NotificationChannelEnum.MAIL.name().equals(channel)) {
            sendMail(notificationId, record);
            return;
        }
        throw new IllegalArgumentException("不支持的通知渠道: " + channel);
    }

    private void sendSms(String notificationId, WinningRecordDO record) {
        Map<String, String> params = new HashMap<>();
        params.put("name", record.getWinnerName());
        params.put("activityName", record.getActivityName());
        params.put("prizeTiers", ActivityPrizeTiersEnum.forName(record.getPrizeTier()).getMessage());
        params.put("prizeName", record.getPrizeName());
        params.put("winningTime", DateUtil.formatTime(record.getWinningTime()));

        // notificationId 作为短信平台的外部流水号，重复发送时下游可以据此去重
        boolean success = smsUtil.sendMessage("SMS_465985911",
                record.getWinnerPhoneNumber().getValue(),
                JacksonUtil.writeValueAsString(params),
                notificationId);
        if (!success) {
            throw new IllegalStateException("短信发送失败");
        }
    }

    private void sendMail(String notificationId, WinningRecordDO record) {
        String content = "Hi，" + record.getWinnerName() + "。恭喜你在"
                + record.getActivityName() + "活动中获得"
                + ActivityPrizeTiersEnum.forName(record.getPrizeTier()).getMessage()
                + "：" + record.getPrizeName() + "。获奖时间为"
                + DateUtil.formatTime(record.getWinningTime()) + "，请尽快领取您的奖励！";
        // 邮件没有业务流水号字段，只在日志里记下 notificationId，出现重复时可以据此排查
        log.info("发送中奖邮件，notificationId={}", notificationId);
        boolean success = Boolean.TRUE.equals(mailUtil.sendSampleMail(
                record.getWinnerEmail(), "中奖通知", content));
        if (!success) {
            throw new IllegalStateException("邮件发送失败");
        }
    }
}
