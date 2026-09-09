package com.example.lotterysystem.dao.mapper;

import com.example.lotterysystem.dao.dataobject.NotificationOutboxDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 通知事务 Outbox 数据访问。
 */
@Mapper
public interface NotificationOutboxMapper {

    @Insert("<script>" +
            " insert into notification_outbox " +
            " (notification_id, activity_id, prize_id, winner_id, channel, status, attempt_count, next_retry_time) " +
            " values " +
            " <foreach collection='items' item='item' separator=','> " +
            " (#{item.notificationId}, #{item.activityId}, #{item.prizeId}, #{item.winnerId}, " +
            "  #{item.channel}, #{item.status}, #{item.attemptCount}, #{item.nextRetryTime}) " +
            " </foreach> " +
            " </script>")
    int batchInsert(@Param("items") List<NotificationOutboxDO> items);

    @Select("select count(1) from notification_outbox " +
            "where activity_id = #{activityId} and prize_id = #{prizeId} and status = #{status}")
    int countByActivityPrizeAndStatus(@Param("activityId") Long activityId,
                                      @Param("prizeId") Long prizeId,
                                      @Param("status") String status);

    @Select("select * from notification_outbox where notification_id = #{notificationId}")
    NotificationOutboxDO selectByNotificationId(@Param("notificationId") String notificationId);

    /** 每轮定时任务只取有限数量的待投递任务。 */
    @Select("select * from notification_outbox " +
            "where status = 'PENDING' " +
            "order by id asc limit #{limit}")
    List<NotificationOutboxDO> selectPending(@Param("limit") int limit);

    /**
     * Confirm 迟到或重复到达时，不允许覆盖消费者已经写入的 DONE 状态。
     */
    @Update("update notification_outbox " +
            "set status = 'PUBLISHED', last_error = null, gmt_modified = now() " +
            "where notification_id = #{notificationId} and status = 'PENDING'")
    int markPublished(@Param("notificationId") String notificationId);

    /** 通知工具明确成功后才把任务结案。 */
    @Update("update notification_outbox " +
            "set status = 'DONE', gmt_modified = now() " +
            "where notification_id = #{notificationId} " +
            "and status in ('PENDING', 'PUBLISHED')")
    int markDone(@Param("notificationId") String notificationId);

    /** 已找不到对应中奖记录时，不应继续发送一条过期通知。 */
    @Update("update notification_outbox " +
            "set status = 'CANCELLED', gmt_modified = now() " +
            "where notification_id = #{notificationId} and status <> 'DONE'")
    int markCancelled(@Param("notificationId") String notificationId);
}
