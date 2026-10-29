package com.example.lotterysystem.service.impl;

import com.example.lotterysystem.controller.param.DrawPrizeParam;
import com.example.lotterysystem.dao.mapper.ActivityPrizeMapper;
import com.example.lotterysystem.service.ActivityService;
import com.example.lotterysystem.service.DrawPrizeService;
import com.example.lotterysystem.service.DrawSettlementService;
import com.example.lotterysystem.service.activitystatus.ActivityStatusManager;
import com.example.lotterysystem.service.dto.ConvertActivityStatusDTO;
import com.example.lotterysystem.service.enums.ActivityPrizeStatusEnum;
import com.example.lotterysystem.service.enums.ActivityStatusEnum;
import com.example.lotterysystem.service.enums.ActivityUserStatusEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.stream.Collectors;

/**
 * 开奖结算。
 *
 * <p>原来是「状态流转」和「写中奖记录」两个独立事务，失败时靠逆向补偿恢复。
 * 重复消息并发时，后一条撞上唯一索引进入补偿，会把前一条已经成功的开奖撤销掉；
 * 两个事务之间进程退出，又会留下「已开奖但没有中奖记录」的状态。</p>
 *
 * <p>现在合成一个事务：第一步用带旧状态条件的 UPDATE 抢占奖品，只有一个事务能抢到，
 * 没抢到的直接返回。失败时整个事务回滚，不再需要补偿。</p>
 */
@Service
public class DrawSettlementServiceImpl implements DrawSettlementService {

    private static final Logger logger = LoggerFactory.getLogger(DrawSettlementServiceImpl.class);

    @Autowired
    private ActivityPrizeMapper activityPrizeMapper;
    @Autowired
    private ActivityStatusManager activityStatusManager;
    @Autowired
    private DrawPrizeService drawPrizeService;
    @Autowired
    private ActivityService activityService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean settle(DrawPrizeParam param) {
        // 1、抢占奖品：INIT -> COMPLETED。另一个事务同时执行时会被行锁挡住，
        //    等它提交后再执行，状态已经不是 INIT，影响 0 行
        if (activityPrizeMapper.completeIfInit(param.getActivityId(), param.getPrizeId()) == 0) {
            logger.info("奖品已经开过，忽略本次开奖消息，activityId={}, prizeId={}",
                    param.getActivityId(), param.getPrizeId());
            return false;
        }

        // 2、人员、活动状态流转（加入当前事务）。奖品在上一步已经是 COMPLETED，PrizeOperator 会跳过
        activityStatusManager.handlerEvent(buildConvertDTO(param));

        // 3、写中奖记录和通知任务（加入当前事务）
        drawPrizeService.saveWinnerRecords(param);

        // 4、事务提交之后再更新活动缓存，回滚时缓存不受影响
        Long activityId = param.getActivityId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    activityService.cacheActivity(activityId);
                } catch (Exception e) {
                    logger.error("开奖已提交，但更新活动缓存失败，activityId={}", activityId, e);
                }
            }
        });
        return true;
    }

    private ConvertActivityStatusDTO buildConvertDTO(DrawPrizeParam param) {
        ConvertActivityStatusDTO convertActivityStatusDTO = new ConvertActivityStatusDTO();
        convertActivityStatusDTO.setActivityId(param.getActivityId());
        convertActivityStatusDTO.setTargetActivityStatus(ActivityStatusEnum.COMPLETED);
        convertActivityStatusDTO.setPrizeId(param.getPrizeId());
        convertActivityStatusDTO.setTargetPrizeStatus(ActivityPrizeStatusEnum.COMPLETED);
        convertActivityStatusDTO.setUserIds(
                param.getWinnerList().stream()
                        .map(DrawPrizeParam.Winner::getUserId)
                        .collect(Collectors.toList())
        );
        convertActivityStatusDTO.setTargetUserStatus(ActivityUserStatusEnum.COMPLETED);
        return convertActivityStatusDTO;
    }
}
