package com.example.lotterysystem.service.impl;


import com.example.lotterysystem.common.errorcode.ServiceErrorCodeConstants;
import com.example.lotterysystem.common.exception.ServiceException;
import com.example.lotterysystem.common.utils.JacksonUtil;
import com.example.lotterysystem.common.utils.RedisUtil;
import com.example.lotterysystem.controller.param.DrawPrizeParam;
import com.example.lotterysystem.controller.param.ShowWinningRecordsParam;
import com.example.lotterysystem.dao.dataobject.*;
import com.example.lotterysystem.dao.mapper.*;
import com.example.lotterysystem.service.DrawPrizeService;
import com.example.lotterysystem.service.dto.WinningRecordDTO;
import com.example.lotterysystem.service.enums.ActivityPrizeStatusEnum;
import com.example.lotterysystem.service.enums.ActivityPrizeTiersEnum;
import com.example.lotterysystem.service.enums.ActivityStatusEnum;
import com.example.lotterysystem.service.enums.NotificationChannelEnum;
import com.example.lotterysystem.service.enums.NotificationOutboxStatusEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

import static com.example.lotterysystem.common.config.DirectRabbitConfig.EXCHANGE_NAME;
import static com.example.lotterysystem.common.config.DirectRabbitConfig.ROUTING;

@Service
public class DrawPrizeServiceImpl implements DrawPrizeService {

    private static final Logger logger = LoggerFactory.getLogger(DrawPrizeServiceImpl.class);

    private final String WINNING_RECORDS_PREFIX = "WINNING_RECORDS_";
    private final Long WINNING_RECORDS_TIMEOUT = 60 * 60 * 24 * 2L;

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivityPrizeMapper activityPrizeMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PrizeMapper prizeMapper;
    @Autowired
    private WinningRecordMapper winningRecordMapper;
    @Autowired
    private NotificationOutboxMapper notificationOutboxMapper;
    @Autowired
    private RedisUtil redisUtil;

    @Override
    public void drawPrize(DrawPrizeParam param) {
        Map<String, String> map = new HashMap<>();
        map.put("messageId", String.valueOf(UUID.randomUUID()));
        map.put("messageData", JacksonUtil.writeValueAsString(param));
        // 发消息: 交换机、绑定的key、消息体
        rabbitTemplate.convertAndSend(EXCHANGE_NAME, ROUTING, map);
        logger.info("mq消息发送成功：map={}", JacksonUtil.writeValueAsString(map));
    }

    @Override
    public Boolean checkDrawPrizeParam(DrawPrizeParam param) {

        ActivityDO activityDO = activityMapper.selectById(param.getActivityId());
        // 奖品是否存在可以从 activity_prize, 原因是保存activity做了本地事务，保证一致性
        ActivityPrizeDO activityPrizeDO = activityPrizeMapper.selectByAPId(
                param.getActivityId(), param.getPrizeId());

        // 活动或奖品是否存在
        if (null == activityDO || null == activityPrizeDO) {
            // throw new ServiceException(ServiceErrorCodeConstants.ACTIVITY_OR_PRIZE_IS_EMPTY);
            logger.info("校验抽奖请求失败！失败原因：{}",
                    ServiceErrorCodeConstants.ACTIVITY_OR_PRIZE_IS_EMPTY.getMsg());
            return false;
        }

        // 活动是否有效
        if (activityDO.getStatus()
                .equalsIgnoreCase(ActivityStatusEnum.COMPLETED.name())) {
            // throw new ServiceException(ServiceErrorCodeConstants.ACTIVITY_COMPLETED);
            logger.info("校验抽奖请求失败！失败原因：{}",
                    ServiceErrorCodeConstants.ACTIVITY_COMPLETED.getMsg());
            return false;
        }

        // 奖品是否有效
        if (activityPrizeDO.getStatus()
                .equalsIgnoreCase(ActivityPrizeStatusEnum.COMPLETED.name())) {
            // throw new ServiceException(ServiceErrorCodeConstants.ACTIVITY_PRIZE_COMPLETED);
            logger.info("校验抽奖请求失败！失败原因：{}",
                    ServiceErrorCodeConstants.ACTIVITY_PRIZE_COMPLETED.getMsg());
            return false;
        }

        // 中奖者人数是否和设置奖品数量一致 3 2
        if (activityPrizeDO.getPrizeAmount() != param.getWinnerList().size()) {
            // throw new ServiceException(ServiceErrorCodeConstants.WINNER_PRIZE_AMOUNT_ERROR);
            logger.info("校验抽奖请求失败！失败原因：{}",
                    ServiceErrorCodeConstants.WINNER_PRIZE_AMOUNT_ERROR.getMsg());
            return false;
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<WinningRecordDO> saveWinnerRecords(DrawPrizeParam param) {
        // 查询相关信息：活动、人员、奖品、活动关联奖品
        ActivityDO activityDO = activityMapper.selectById(param.getActivityId());
        List<UserDO> userDOList = userMapper.batchSelectByIds(
                param.getWinnerList()
                        .stream()
                        .map(DrawPrizeParam.Winner::getUserId)
                        .collect(Collectors.toList())
        );
        PrizeDO prizeDO = prizeMapper.selectById(param.getPrizeId());
        ActivityPrizeDO activityPrizeDO =
                activityPrizeMapper.selectByAPId(param.getActivityId(), param.getPrizeId());

        // 构造中奖者记录，保存
        List<WinningRecordDO> winningRecordDOList = userDOList.stream()
                .map(userDO -> {
                    WinningRecordDO winningRecordDO = new WinningRecordDO();
                    winningRecordDO.setActivityId(activityDO.getId());
                    winningRecordDO.setActivityName(activityDO.getActivityName());
                    winningRecordDO.setPrizeId(prizeDO.getId());
                    winningRecordDO.setPrizeName(prizeDO.getName());
                    winningRecordDO.setPrizeTier(activityPrizeDO.getPrizeTiers());
                    winningRecordDO.setWinnerId(userDO.getId());
                    winningRecordDO.setWinnerName(userDO.getUserName());
                    winningRecordDO.setWinnerEmail(userDO.getEmail());
                    winningRecordDO.setWinnerPhoneNumber(userDO.getPhoneNumber());
                    winningRecordDO.setWinningTime(param.getWinningTime());
                    return winningRecordDO;
                }).collect(Collectors.toList());
        winningRecordMapper.batchInsert(winningRecordDOList);

        // 与中奖记录在同一个本地事务中落库：两者要么一起提交，要么一起回滚。
        // 后续投递器只扫描 PENDING 任务，因此不会因为应用在提交后宕机而丢失通知任务。
        List<NotificationOutboxDO> notificationOutboxDOList =
                buildPendingNotificationTasks(winningRecordDOList);
        notificationOutboxMapper.batchInsert(notificationOutboxDOList);

        // 缓存中奖者记录
        // 1、缓存奖品维度中奖记录(WinningRecord_activityId_prizeId, winningRecordDOList（奖品维度的中奖名单）)
        cacheWinningRecords(param.getActivityId() + "_" + param.getPrizeId(),
                winningRecordDOList,
                WINNING_RECORDS_TIMEOUT);

        // 2、缓存活动维度中奖记录(WinningRecord_activityId, winningRecordDOList(活动维度的中奖名单))
        // 当活动已完成再去存放活动维度中奖记录
        if (activityDO.getStatus()
                .equalsIgnoreCase(ActivityStatusEnum.COMPLETED.name())) {
            // 查询活动维度的全量中奖记录
            List<WinningRecordDO> allList = winningRecordMapper.selectByActivityId(param.getActivityId());
            cacheWinningRecords(String.valueOf(param.getActivityId()),
                    allList,
                    WINNING_RECORDS_TIMEOUT);
        }

        return winningRecordDOList;

    }

    /**
     * 每名中奖者创建一条短信和一条邮件通知任务。
     * 通知 ID 在落库时生成，后续既作为 MQ 消息标识，也作为消费者幂等键。
     */
    private List<NotificationOutboxDO> buildPendingNotificationTasks(
            List<WinningRecordDO> winningRecordDOList) {
        List<NotificationOutboxDO> tasks = new ArrayList<>();
        Date now = new Date();
        for (WinningRecordDO winningRecordDO : winningRecordDOList) {
            tasks.add(buildPendingNotificationTask(winningRecordDO,
                    NotificationChannelEnum.SMS.name(), now));
            tasks.add(buildPendingNotificationTask(winningRecordDO,
                    NotificationChannelEnum.MAIL.name(), now));
        }
        return tasks;
    }

    private NotificationOutboxDO buildPendingNotificationTask(WinningRecordDO winningRecordDO,
                                                               String channel,
                                                               Date nextRetryTime) {
        NotificationOutboxDO task = new NotificationOutboxDO();
        task.setNotificationId(UUID.randomUUID().toString());
        task.setActivityId(winningRecordDO.getActivityId());
        task.setPrizeId(winningRecordDO.getPrizeId());
        task.setWinnerId(winningRecordDO.getWinnerId());
        task.setChannel(channel);
        task.setStatus(NotificationOutboxStatusEnum.PENDING.name());
        task.setAttemptCount(0);
        task.setNextRetryTime(nextRetryTime);
        return task;
    }

    @Override
    public void deleteRecords(Long activityId, Long prizeId) {
        if (null == activityId) {
            logger.warn("要删除中奖记录相关的活动id为空！");
            return;
        }
        // 删除数据表
        winningRecordMapper.deleteRecords(activityId, prizeId);

        // 删除缓存（奖品维度、活动维度）
        if (null != prizeId) {
            deleteWinningRecords(activityId + "_" + prizeId);
        }
        // 无论是否传递了prizeId，都需要删除活动维度的中奖记录缓存：
        // 如果传递了prizeId, 证明奖品未抽奖，必须删除活动维度的缓存记录
        // 如果没有传递prizeId，就只是删除活动维度的信息
        deleteWinningRecords(String.valueOf(activityId));
    }

    @Override
    public List<WinningRecordDTO> getRecords(ShowWinningRecordsParam param) {
        // 查询redis: 奖品、活动
        String key = null == param.getPrizeId()
                ? String.valueOf(param.getActivityId())
                : param.getActivityId() + "_" + param.getPrizeId();
        List<WinningRecordDO> winningRecordDOList = getWinningRecords(key);
        if (!CollectionUtils.isEmpty(winningRecordDOList)) {
            return convertToWinningRecordDTOList(winningRecordDOList);
        }
        // 如果redis不存在，查库
        winningRecordDOList = winningRecordMapper.selectByActivityIdOrPrizeId(
                param.getActivityId(), param.getPrizeId());
        // 存放记录到redis
        if (CollectionUtils.isEmpty(winningRecordDOList)) {
            logger.info("查询的中奖记录为空！param:{}",
                    JacksonUtil.writeValueAsString(param));
            return Arrays.asList();
        }
        cacheWinningRecords(key, winningRecordDOList, WINNING_RECORDS_TIMEOUT);
        return convertToWinningRecordDTOList(winningRecordDOList);
    }

    private List<WinningRecordDTO> convertToWinningRecordDTOList(
            List<WinningRecordDO> winningRecordDOList) {
        if (CollectionUtils.isEmpty(winningRecordDOList)) {
            return Arrays.asList();
        }
        return winningRecordDOList.stream()
                .map(winningRecordDO -> {
                    WinningRecordDTO winningRecordDTO = new WinningRecordDTO();
                    winningRecordDTO.setWinnerId(winningRecordDO.getWinnerId());
                    winningRecordDTO.setWinnerName(winningRecordDO.getWinnerName());
                    winningRecordDTO.setPrizeName(winningRecordDO.getPrizeName());
                    winningRecordDTO.setPrizeTier(
                            ActivityPrizeTiersEnum.forName(winningRecordDO.getPrizeTier()));
                    winningRecordDTO.setWinningTime(winningRecordDO.getWinningTime());
                    return winningRecordDTO;
                }).collect(Collectors.toList());
    }


    /**
     * 从缓存中删除中奖记录
     *
     * @param key
     */
    private void deleteWinningRecords(String key) {
        try {
            if (redisUtil.hasKey(WINNING_RECORDS_PREFIX + key)) {
                // 存在再删除
                redisUtil.del(WINNING_RECORDS_PREFIX + key);
            }
        } catch (Exception e) {
            logger.error("删除中奖记录缓存异常，key:{}", key);
        }
    }

    /**
     * 缓存中奖记录
     *
     * @param key
     * @param winningRecordDOList
     * @param time
     */
    private void cacheWinningRecords(String key,
                                     List<WinningRecordDO> winningRecordDOList,
                                     Long time) {
        String str = "";
        try {
            if (!StringUtils.hasText(key)
                    || CollectionUtils.isEmpty(winningRecordDOList)) {
                logger.warn("要缓存的内容为空！key:{}, value:{}",
                        key, JacksonUtil.writeValueAsString(winningRecordDOList));
                return;
            }

            str = JacksonUtil.writeValueAsString(winningRecordDOList);
            redisUtil.set(WINNING_RECORDS_PREFIX + key,
                    str,
                    time);
        } catch (Exception e) {
            logger.error("缓存中奖记录异常！key:{}, value:{}", WINNING_RECORDS_PREFIX + key, str);
        }

    }

    /**
     * 从缓存中获取中奖记录
     *
     * @param key
     * @return
     */
    private List<WinningRecordDO> getWinningRecords(String key) {
        try {
            if (!StringUtils.hasText(key)) {
                logger.warn("要从缓存中查询中奖记录的key为空！");
                return Arrays.asList();
            }
            String str = redisUtil.get(WINNING_RECORDS_PREFIX + key);
            if (!StringUtils.hasText(str)) {
                return Arrays.asList();
            }

            return JacksonUtil.readListValue(str, WinningRecordDO.class);
        } catch (Exception e) {
            logger.error("从缓存中查询中奖记录异常！key:{}", WINNING_RECORDS_PREFIX + key);
            return Arrays.asList();
        }
    }

}
