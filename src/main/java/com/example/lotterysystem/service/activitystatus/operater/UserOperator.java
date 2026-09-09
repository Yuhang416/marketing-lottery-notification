package com.example.lotterysystem.service.activitystatus.operater;

import com.example.lotterysystem.dao.dataobject.ActivityUserDO;
import com.example.lotterysystem.dao.mapper.ActivityUserMapper;
import com.example.lotterysystem.service.dto.ConvertActivityStatusDTO;
import com.example.lotterysystem.service.enums.ActivityUserStatusEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Component
public class UserOperator extends AbstractActivityOperator {

    @Autowired
    private ActivityUserMapper activityUserMapper;

    @Override
    public Integer sequence() {
        return 1;
    }

    @Override
    public Boolean needConvert(ConvertActivityStatusDTO convertActivityStatusDTO) {
        Long activityId = convertActivityStatusDTO.getActivityId();
        List<Long> userIds = convertActivityStatusDTO.getUserIds();
        ActivityUserStatusEnum targetUserStatus = convertActivityStatusDTO.getTargetUserStatus();
        if (null == activityId
                || CollectionUtils.isEmpty(userIds)
                || null == targetUserStatus) {
            return false;
        }
        List<ActivityUserDO> activityUserDOList =
                activityUserMapper.batchSelectByAUIds(activityId, userIds);
        if (CollectionUtils.isEmpty(activityUserDOList)) {
            return false;
        }

        for (ActivityUserDO auDO : activityUserDOList) {
            if (auDO.getStatus()
                    .equalsIgnoreCase(targetUserStatus.name())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Boolean convert(ConvertActivityStatusDTO convertActivityStatusDTO) {
        Long activityId = convertActivityStatusDTO.getActivityId();
        List<Long> userIds = convertActivityStatusDTO.getUserIds();
        ActivityUserStatusEnum targetUserStatus = convertActivityStatusDTO.getTargetUserStatus();
        try {
            activityUserMapper.batchUpdateStatus(activityId, userIds, targetUserStatus.name());
            return true;
        } catch (Exception e) {
            return false;
        }

    }
}
