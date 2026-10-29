package com.example.lotterysystem.service.mq;

import cn.hutool.core.date.DateUtil;
import com.example.lotterysystem.common.utils.JacksonUtil;
import com.example.lotterysystem.common.utils.MailUtil;
import com.example.lotterysystem.common.utils.SMSUtil;
import com.example.lotterysystem.controller.param.DrawPrizeParam;
import com.example.lotterysystem.dao.dataobject.WinningRecordDO;
import com.example.lotterysystem.service.DrawPrizeService;
import com.example.lotterysystem.service.DrawSettlementService;
import com.example.lotterysystem.service.enums.ActivityPrizeTiersEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.lotterysystem.common.config.DirectRabbitConfig.QUEUE_NAME;

@Component
@RabbitListener(queues = QUEUE_NAME)
public class MqReceiver {

    private static final Logger logger = LoggerFactory.getLogger(MqReceiver.class);
    @Autowired
    private DrawPrizeService drawPrizeService;
    @Autowired
    private DrawSettlementService drawSettlementService;
    @Autowired
    @Qualifier("asyncServiceExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    @Autowired
    private MailUtil mailUtil;
    @Autowired
    private SMSUtil smsUtil;

    @RabbitHandler
    public void process(Map<String, String> message) {
        // 成功接收到队列中的消息
        logger.info("MQ成功接收到消息，message:{}",
                JacksonUtil.writeValueAsString(message));
        String paramString = message.get("messageData");
        DrawPrizeParam param = JacksonUtil.readValue(paramString, DrawPrizeParam.class);

        // 校验抽奖请求是否有效。这里只是快速判断，重复消息的正确性由 settle() 里的条件更新保证
        if (!drawPrizeService.checkDrawPrizeParam(param)) {
            return;
        }

        // 抢占奖品、状态流转、写中奖记录和通知任务，在同一个事务里完成。
        // 其他异常直接抛出：事务整体回滚，由监听器重试，重试耗尽进入死信队列
        try {
            drawSettlementService.settle(param);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：说明这批中奖记录已经写过，视为已处理。
            // 必须在事务方法外面捕获，在事务里吞掉会导致提交时报错
            logger.warn("中奖记录已存在，视为已处理，activityId={}, prizeId={}",
                    param.getActivityId(), param.getPrizeId());
        }

        // 通知中奖者（邮箱、短信）
        // 通知任务已在 settle() 的本地事务中写入 notification_outbox。
        // 后续由 Outbox 投递器发送到通知专用 MQ；这里不能再直接提交线程池任务，
        // 否则会绕开 Outbox，造成重复通知。
        // syncExecute(winningRecordDOList);
    }

    /**
     * 并发处理抽奖后续流程
     *
     * @param winningRecordDOList
     */
    private void syncExecute(List<WinningRecordDO> winningRecordDOList) {
        // 通过线程池 threadPoolTaskExecutor
        // 扩展：加入策略模式或者其他设计模式来完成后续的异步操作
        // 短信通知
        threadPoolTaskExecutor.execute(()->sendMessage(winningRecordDOList));
        // 邮件通知
        threadPoolTaskExecutor.execute(()->sendMail(winningRecordDOList));
    }

    /**
     * 发邮件
     *
     * @param winningRecordDOList
     */
    private void sendMail(List<WinningRecordDO> winningRecordDOList) {
        if (CollectionUtils.isEmpty(winningRecordDOList)) {
            logger.info("中奖列表为空，不用发邮件！");
            return;
        }
        for (WinningRecordDO winningRecordDO : winningRecordDOList) {
            // Hi,胡一博。恭喜你在抽奖活动活动中获得二等奖:吹风机。获奖奖时间为18:18:44,请尽快领取您的奖励
            String context = "Hi，" + winningRecordDO.getWinnerName() + "。恭喜你在"
                        + winningRecordDO.getActivityName() + "活动中获得"
                        + ActivityPrizeTiersEnum.forName(winningRecordDO.getPrizeTier()).getMessage()
                        + "：" + winningRecordDO.getPrizeName() + "。获奖时间为"
                        + DateUtil.formatTime(winningRecordDO.getWinningTime()) + "，请尽快领 取您的奖励！";
            mailUtil.sendSampleMail(winningRecordDO.getWinnerEmail(),
                    "中奖通知", context);
        }
    }

    /**
     * 发短信
     *
     * @param winningRecordDOList
     */
    private void sendMessage(List<WinningRecordDO> winningRecordDOList) {
        if (CollectionUtils.isEmpty(winningRecordDOList)) {
            logger.info("中奖列表为空，不用发短信！");
            return;
        }
        for (WinningRecordDO winningRecordDO : winningRecordDOList) {
            Map<String, String> map = new HashMap<>();
            map.put("name", winningRecordDO.getWinnerName());
            map.put("activityName", winningRecordDO.getActivityName());
            map.put("prizeTiers", ActivityPrizeTiersEnum.forName(winningRecordDO.getPrizeTier()).getMessage());
            map.put("prizeName", winningRecordDO.getPrizeName());
            map.put("winningTime", DateUtil.formatTime(winningRecordDO.getWinningTime()));
            smsUtil.sendMessage("SMS_465985911",
                    winningRecordDO.getWinnerPhoneNumber().getValue(),
                    JacksonUtil.writeValueAsString(map));
        }
    }

}
