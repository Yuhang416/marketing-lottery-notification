package com.example.lotterysystem.dao.dataobject;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 死信记录：进入死信队列的消息，等人工排查后重放。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DeadLetterRecordDO extends BaseDO {
    /** 消息进入死信前所在的队列。 */
    private String sourceQueue;
    /** 消息原来发往的交换机和路由键，重放时发回这里。 */
    private String sourceExchange;
    private String sourceRoutingKey;
    /** 消息体（JSON）。 */
    private String messageBody;
    /** x-death 里的原因：rejected / expired / maxlen 等。 */
    private String reason;
    private Long deathCount;
    private Date firstDeathTime;
    /** PENDING 待处理 / REPLAYED 已重放。 */
    private String status;
    private Date replayTime;
}
