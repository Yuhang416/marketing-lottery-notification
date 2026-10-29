package com.example.lotterysystem.dao.mapper;

import com.example.lotterysystem.dao.dataobject.NotificationOutboxDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
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

    /**
     * 领取到期的待投递任务，必须在事务里调用。
     * skip locked 会跳过其他事务正在领取的行，两个实例同时领取拿到的一定是不同的任务。
     * now 由应用传入：应用写入时间时按 JDBC 的 serverTimezone 换算，数据库的 now() 用的是另一个时区，不能混用。
     */
    @Select("select * from notification_outbox " +
            "where status = 'PENDING' and next_retry_time <= #{now} " +
            "order by next_retry_time, id limit #{limit} " +
            "for update skip locked")
    List<NotificationOutboxDO> selectClaimableForUpdate(@Param("now") Date now,
                                                        @Param("limit") int limit);

    /** 领走任务：尝试次数加一，把下次可领取的时间推后，当作租约。 */
    @Update("update notification_outbox " +
            "set attempt_count = attempt_count + 1, next_retry_time = #{nextRetryTime}, gmt_modified = now() " +
            "where id = #{id}")
    int extendLease(@Param("id") Long id, @Param("nextRetryTime") Date nextRetryTime);

    /** 重试达到上限，停止投递。 */
    @Update("update notification_outbox " +
            "set status = 'FAILED', last_error = #{lastError}, gmt_modified = now() " +
            "where id = #{id} and status = 'PENDING'")
    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);

    /** 投递失败时只记录原因，任务保持 PENDING，租约到期后会被重新领取。 */
    @Update("update notification_outbox " +
            "set last_error = #{lastError}, gmt_modified = now() " +
            "where notification_id = #{notificationId} and status = 'PENDING'")
    int recordError(@Param("notificationId") String notificationId, @Param("lastError") String lastError);

    /**
     * Confirm 迟到或重复到达时，不允许覆盖消费者已经写入的 DONE 状态。
     */
    @Update("update notification_outbox " +
            "set status = 'PUBLISHED', last_error = null, gmt_modified = now() " +
            "where notification_id = #{notificationId} and status = 'PENDING'")
    int markPublished(@Param("notificationId") String notificationId);

    /**
     * 通知工具明确成功后才把任务结案。
     * 包含 FAILED：消息可能已经进了队列、只是确认丢了，任务被重试到上限；消息之后仍会被消费并发出，状态应以实际发送为准。
     */
    @Update("update notification_outbox " +
            "set status = 'DONE', gmt_modified = now() " +
            "where notification_id = #{notificationId} " +
            "and status in ('PENDING', 'PUBLISHED', 'FAILED')")
    int markDone(@Param("notificationId") String notificationId);

    /** 已找不到对应中奖记录时，不应继续发送一条过期通知。 */
    @Update("update notification_outbox " +
            "set status = 'CANCELLED', gmt_modified = now() " +
            "where notification_id = #{notificationId} and status <> 'DONE'")
    int markCancelled(@Param("notificationId") String notificationId);
}
