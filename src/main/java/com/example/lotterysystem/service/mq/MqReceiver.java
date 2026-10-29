package com.example.lotterysystem.service.mq;

import com.example.lotterysystem.common.utils.JacksonUtil;
import com.example.lotterysystem.controller.param.DrawPrizeParam;
import com.example.lotterysystem.service.DrawPrizeService;
import com.example.lotterysystem.service.DrawSettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

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
        // 通知任务写入 notification_outbox 后由 Outbox 投递器发送，这里不直接发通知。
        // 其他异常直接抛出：事务整体回滚，由监听器重试，重试耗尽进入死信队列
        try {
            drawSettlementService.settle(param);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：说明这批中奖记录已经写过，视为已处理。
            // 必须在事务方法外面捕获，在事务里吞掉会导致提交时报错
            logger.warn("中奖记录已存在，视为已处理，activityId={}, prizeId={}",
                    param.getActivityId(), param.getPrizeId());
        }
    }
}
