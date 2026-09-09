package com.example.lotterysystem.dao.dataobject;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 开奖成功后待发送的通知任务。
 *
 * <p>这张表只记录通知任务与状态，不重复存储手机号、邮箱等敏感数据。
 * 后续通知消费者根据活动、奖品和中奖人 ID 查询 {@link WinningRecordDO} 快照。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NotificationOutboxDO extends BaseDO {

    /** 通知的全局幂等键，也是后续 MQ 消息标识。 */
    private String notificationId;

    private Long activityId;

    private Long prizeId;

    private Long winnerId;

    /** SMS 或 MAIL。 */
    private String channel;

    /** PENDING、PUBLISHED、DONE、CANCELLED。 */
    private String status;

    /** 向 RabbitMQ 投递的尝试次数。 */
    private Integer attemptCount;

    private Date nextRetryTime;

    private String lastError;
}
