-- 死信记录：开奖、通知两个死信队列里的消息都落到这张表，等人工排查后重放。
-- 死信消费者只记录、告警，不会自动把消息发回原队列。
CREATE TABLE IF NOT EXISTS dead_letter_record (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    source_queue VARCHAR(64) NOT NULL COMMENT '消息进入死信前所在的队列',
    source_exchange VARCHAR(64) NOT NULL COMMENT '消息原来发往的交换机，重放时发回这里',
    source_routing_key VARCHAR(128) NOT NULL COMMENT '消息原来的路由键',
    message_body TEXT NOT NULL COMMENT '消息体（JSON）',
    reason VARCHAR(32) DEFAULT NULL COMMENT 'x-death 里的原因：rejected / expired / maxlen 等',
    death_count INT DEFAULT NULL COMMENT 'x-death 里记录的进入死信次数',
    first_death_time DATETIME DEFAULT NULL COMMENT '第一次进入死信的时间',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING 待处理 / REPLAYED 已重放',
    replay_time DATETIME DEFAULT NULL COMMENT '人工重放的时间',
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='死信记录';
