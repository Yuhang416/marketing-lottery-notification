package com.example.lotterysystem.service.activitystatus.impl;

import com.example.lotterysystem.common.errorcode.ServiceErrorCodeConstants;
import com.example.lotterysystem.common.exception.ServiceException;
import com.example.lotterysystem.service.ActivityService;
import com.example.lotterysystem.service.activitystatus.ActivityStatusManager;
import com.example.lotterysystem.service.activitystatus.operater.AbstractActivityOperator;
import com.example.lotterysystem.service.dto.ConvertActivityStatusDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Component
public class ActivityStatusManagerImpl implements ActivityStatusManager {

    private static final Logger logger = LoggerFactory.getLogger(ActivityStatusManagerImpl.class);

    @Autowired
    private List<AbstractActivityOperator> operators;
    @Autowired
    private ActivityService activityService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handlerEvent(ConvertActivityStatusDTO convertActivityStatusDTO) {
        // 1、活动状态扭转有依赖性，导致代码维护性差
        // 2、状态扭转条件可能会扩展，当前写法，扩展性差，维护性差

        if (CollectionUtils.isEmpty(operators)) {
            logger.warn("活动状态处理器为空！");
            return;
        }
        Boolean update = false;
        // 第一阶段：只处理 sequence = 1 的人员、奖品。
        update = processConvertStatus(convertActivityStatusDTO, 1);
        // 第二阶段：只处理 sequence = 2 的活动。
        update = processConvertStatus(convertActivityStatusDTO, 2) || update;
        // 更新缓存
        if (update) {
            activityService.cacheActivity(convertActivityStatusDTO.getActivityId());
        }

    }

    @Override
    public void rollbackHandlerEvent(ConvertActivityStatusDTO convertActivityStatusDTO) {
        // operators：人员、奖品、活动
        // 活动是否需要回滚？？ 绝对需要，
        // 原因：奖品都恢复成INIT，那么这个活动下的奖品绝对没抽完
        for (AbstractActivityOperator operator : operators) {
            operator.convert(convertActivityStatusDTO);
        }

        // 缓存更新
        activityService.cacheActivity(convertActivityStatusDTO.getActivityId());
    }

    /**
     * 扭转状态
     *
     * @param convertActivityStatusDTO
     * @param sequence 当前处理阶段
     * @return
     */
    private Boolean processConvertStatus(ConvertActivityStatusDTO convertActivityStatusDTO,
                                         int sequence) {
        Boolean update = false;
        for (AbstractActivityOperator operator : operators) {
            // 不是当前阶段，或当前状态已经是目标状态时跳过。
            if (operator.sequence() != sequence
                    || !operator.needConvert(convertActivityStatusDTO)) {
                continue;
            }

            // 需要转换：转换
            if (!operator.convert(convertActivityStatusDTO)) {
                logger.error("{}状态转换失败！", operator.getClass().getName());
                throw new ServiceException(ServiceErrorCodeConstants.ACTIVITY_STATUS_CONVERT_ERROR);
            }

            update = true;
        }

        // 返回
        return update;
    }
}
