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
import com.example.lotterysystem.service.enums.NotificationOutboxStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

import static com.example.lotterysystem.common.config.NotificationRabbitConfig.NOTIFICATION_QUEUE_NAME;

/**
 * 通知专线的消费者：真正调用短信/邮件工具，并在成功后结束 Outbox 任务。
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

    @RabbitHandler
    public void process(Map<String, String> message) {
        String notificationId = message.get("notificationId");
        if (!StringUtils.hasText(notificationId)) {
            return;
        }

        // 1. 查任务并做幂等拦截。
        NotificationOutboxDO task = outboxMapper.selectByNotificationId(notificationId);
        // 允许 PENDING：消息可能先到消费者，随后 Confirm 回调才把任务改成 PUBLISHED。
        // DONE、CANCELLED 的消息属于重复消息，直接结束即可。
        if (task == null
                || NotificationOutboxStatusEnum.DONE.name().equals(task.getStatus())
                || NotificationOutboxStatusEnum.CANCELLED.name().equals(task.getStatus())) {
            return;
        }

        // 2. Claim-Check：根据任务中的业务 ID 回查中奖详情。
        WinningRecordDO record = winningRecordMapper.selectByActivityPrizeAndWinner(
                task.getActivityId(), task.getPrizeId(), task.getWinnerId());
        if (record == null) {
            // 例如开奖后来被补偿，中奖记录已不存在，此时不应该再发通知。
            outboxMapper.markCancelled(notificationId);
            return;
        }

        // 3. 真实发送通知。发送失败会抛异常，RabbitMQ 会按现有配置重试该消息。
        sendNotice(task.getChannel(), record);

        // 4. 终态闭环：只有工具类明确发送成功才更新为 DONE。
        outboxMapper.markDone(notificationId);
        log.info("通知发送完成，notificationId={}", notificationId);
    }

    private void sendNotice(String channel, WinningRecordDO record) {
        if (NotificationChannelEnum.SMS.name().equals(channel)) {
            sendSms(record);
            return;
        }
        if (NotificationChannelEnum.MAIL.name().equals(channel)) {
            sendMail(record);
            return;
        }
        throw new IllegalArgumentException("不支持的通知渠道: " + channel);
    }

    private void sendSms(WinningRecordDO record) {
        Map<String, String> params = new HashMap<>();
        params.put("name", record.getWinnerName());
        params.put("activityName", record.getActivityName());
        params.put("prizeTiers", ActivityPrizeTiersEnum.forName(record.getPrizeTier()).getMessage());
        params.put("prizeName", record.getPrizeName());
        params.put("winningTime", DateUtil.formatTime(record.getWinningTime()));

        boolean success = smsUtil.sendMessage("SMS_465985911",
                record.getWinnerPhoneNumber().getValue(),
                JacksonUtil.writeValueAsString(params));
        if (!success) {
            throw new IllegalStateException("短信发送失败");
        }
    }

    private void sendMail(WinningRecordDO record) {
        String content = "Hi，" + record.getWinnerName() + "。恭喜你在"
                + record.getActivityName() + "活动中获得"
                + ActivityPrizeTiersEnum.forName(record.getPrizeTier()).getMessage()
                + "：" + record.getPrizeName() + "。获奖时间为"
                + DateUtil.formatTime(record.getWinningTime()) + "，请尽快领取您的奖励！";
        boolean success = Boolean.TRUE.equals(mailUtil.sendSampleMail(
                record.getWinnerEmail(), "中奖通知", content));
        if (!success) {
            throw new IllegalStateException("邮件发送失败");
        }
    }
}
