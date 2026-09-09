-- 开奖结算成功后创建的通知任务。
-- 一位中奖者会生成 SMS、MAIL 两条任务；真正的消息投递和发送由后续链路处理。
CREATE TABLE IF NOT EXISTS notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    notification_id VARCHAR(64) NOT NULL COMMENT '通知幂等键，也是后续 MQ 消息标识',
    activity_id BIGINT NOT NULL COMMENT '活动 ID',
    prize_id BIGINT NOT NULL COMMENT '奖品 ID',
    winner_id BIGINT NOT NULL COMMENT '中奖人 ID',
    channel VARCHAR(16) NOT NULL COMMENT '通知渠道：SMS / MAIL',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING / PUBLISHED / DONE / CANCELLED',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '向 MQ 投递的尝试次数',
    next_retry_time DATETIME NOT NULL COMMENT '下次允许投递的时间',
    last_error VARCHAR(500) DEFAULT NULL COMMENT '最近一次投递或通知失败原因',
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_id (notification_id),
    UNIQUE KEY uk_winner_channel (activity_id, prize_id, winner_id, channel),
    KEY idx_status_retry (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知事务 Outbox';
