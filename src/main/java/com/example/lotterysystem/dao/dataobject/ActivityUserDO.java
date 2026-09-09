package com.example.lotterysystem.dao.dataobject;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ActivityUserDO extends BaseDO {

    /**
     * 关联的活动id
     */
    private Long activityId;

    /**
     * 关联的人员id
     */
    private Long userId;

    /**
     * 姓名
     */
    private String userName;

    /**
     * 关联的人员状态
     */
    private String status;


}
