package com.example.lotterysystem.service.activitystatus;

import com.example.lotterysystem.service.dto.ConvertActivityStatusDTO;

public interface ActivityStatusManager {

    /**
     * 处理活动相关状态转换
     *
     * @param convertActivityStatusDTO
     */
    void handlerEvent(ConvertActivityStatusDTO convertActivityStatusDTO);


    /**
     * 回滚处理活动相关状态
     *
     * @param convertActivityStatusDTO
     */
    void rollbackHandlerEvent(ConvertActivityStatusDTO convertActivityStatusDTO);

}
