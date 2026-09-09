package com.example.lotterysystem;

import com.example.lotterysystem.controller.param.DrawPrizeParam;
import com.example.lotterysystem.dao.dataobject.ActivityDO;
import com.example.lotterysystem.dao.dataobject.ActivityPrizeDO;
import com.example.lotterysystem.dao.dataobject.ActivityUserDO;
import com.example.lotterysystem.dao.dataobject.Encrypt;
import com.example.lotterysystem.dao.dataobject.PrizeDO;
import com.example.lotterysystem.dao.dataobject.UserDO;
import com.example.lotterysystem.dao.mapper.ActivityMapper;
import com.example.lotterysystem.dao.mapper.ActivityPrizeMapper;
import com.example.lotterysystem.dao.mapper.ActivityUserMapper;
import com.example.lotterysystem.dao.mapper.NotificationOutboxMapper;
import com.example.lotterysystem.dao.mapper.PrizeMapper;
import com.example.lotterysystem.dao.mapper.UserMapper;
import com.example.lotterysystem.dao.mapper.WinningRecordMapper;
import com.example.lotterysystem.service.DrawPrizeService;
import com.example.lotterysystem.service.enums.ActivityPrizeStatusEnum;
import com.example.lotterysystem.service.enums.ActivityPrizeTiersEnum;
import com.example.lotterysystem.service.enums.ActivityStatusEnum;
import com.example.lotterysystem.service.enums.ActivityUserStatusEnum;
import com.example.lotterysystem.service.enums.NotificationOutboxStatusEnum;
import com.example.lotterysystem.service.enums.UserIdentityEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 本地 RabbitMQ + MySQL 集成测试。
 *
 * 每次运行会清理并重新插入带有“自动化测试”标记的数据，因此不依赖老师电脑中的固定 ID。
 * 测试成功后的数据会保留在本地数据库，便于在页面或 SQL 中查看；下一次运行前会自动清理。
 */
@SpringBootTest
class DrawPrizeTest {

    private static final String TEST_ACTIVITY_NAME = "自动化测试-抽奖活动";
    private static final String TEST_PRIZE_NAME = "自动化测试-一等奖奖品";
    private static final String TEST_EMAIL = "lottery-test@local.test";
    private static final String TEST_PHONE = "13900009999";

    @Autowired
    private DrawPrizeService drawPrizeService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private PrizeMapper prizeMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ActivityPrizeMapper activityPrizeMapper;
    @Autowired
    private ActivityUserMapper activityUserMapper;
    @Autowired
    private WinningRecordMapper winningRecordMapper;
    @Autowired
    private NotificationOutboxMapper notificationOutboxMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long activityId;
    private Long prizeId;
    private Long userId;

    @BeforeEach
    void setUp() {
        removePreviousTestData();

        ActivityDO activity = new ActivityDO();
        activity.setActivityName(TEST_ACTIVITY_NAME);
        activity.setDescription("DrawPrizeTest 自动创建，请勿作为正式业务数据使用");
        activity.setStatus(ActivityStatusEnum.RUNNING.name());
        activityMapper.insert(activity);
        activityId = activity.getId();

        PrizeDO prize = new PrizeDO();
        prize.setName(TEST_PRIZE_NAME);
        prize.setDescription("DrawPrizeTest 自动创建的测试奖品");
        prize.setPrice(BigDecimal.valueOf(1));
        prize.setImageUrl("/images/test-prize.png");
        prizeMapper.insert(prize);
        prizeId = prize.getId();

        UserDO user = new UserDO();
        user.setUserName("抽奖测试用户");
        user.setEmail(TEST_EMAIL);
        user.setPhoneNumber(new Encrypt(TEST_PHONE));
        user.setPassword("test-only-password");
        user.setIdentity(UserIdentityEnum.NORMAL.name());
        userMapper.insert(user);
        userId = user.getId();

        ActivityPrizeDO activityPrize = new ActivityPrizeDO();
        activityPrize.setActivityId(activityId);
        activityPrize.setPrizeId(prizeId);
        activityPrize.setPrizeAmount(1L);
        activityPrize.setPrizeTiers(ActivityPrizeTiersEnum.FIRST_PRIZE.name());
        activityPrize.setStatus(ActivityPrizeStatusEnum.INIT.name());
        activityPrizeMapper.batchInsert(List.of(activityPrize));

        ActivityUserDO activityUser = new ActivityUserDO();
        activityUser.setActivityId(activityId);
        activityUser.setUserId(userId);
        activityUser.setUserName(user.getUserName());
        activityUser.setStatus(ActivityUserStatusEnum.INIT.name());
        activityUserMapper.batchInsert(List.of(activityUser));
    }

    /**
     * MySQL + RabbitMQ 的真实集成测试：
     * 中奖记录和两条通知任务一起落库，随后经 Outbox 投递器、通知消费者到达 DONE。
     */
    @Test
    void shouldCompleteNotificationOutboxLifecycle() throws InterruptedException {
        DrawPrizeParam param = new DrawPrizeParam();
        param.setActivityId(activityId);
        param.setPrizeId(prizeId);
        param.setWinningTime(new Date());

        DrawPrizeParam.Winner winner = new DrawPrizeParam.Winner();
        winner.setUserId(userId);
        winner.setUserName("抽奖测试用户");
        param.setWinnerList(List.of(winner));

        drawPrizeService.drawPrize(param);

        awaitWinningRecord();

        assertEquals(1, winningRecordMapper.countByAPId(activityId, prizeId));
        awaitOutboxDone();
        assertEquals(2, notificationOutboxMapper.countByActivityPrizeAndStatus(
                activityId, prizeId, NotificationOutboxStatusEnum.DONE.name()));
        assertEquals(0, notificationOutboxMapper.countByActivityPrizeAndStatus(
                activityId, prizeId, NotificationOutboxStatusEnum.PENDING.name()));
        assertEquals(0, notificationOutboxMapper.countByActivityPrizeAndStatus(
                activityId, prizeId, NotificationOutboxStatusEnum.PUBLISHED.name()));
        assertEquals(ActivityStatusEnum.COMPLETED.name(),
                activityMapper.selectById(activityId).getStatus());
        assertEquals(ActivityPrizeStatusEnum.COMPLETED.name(),
                activityPrizeMapper.selectByAPId(activityId, prizeId).getStatus());
        assertEquals(ActivityUserStatusEnum.COMPLETED.name(),
                activityUserMapper.batchSelectByAUIds(activityId, List.of(userId)).get(0).getStatus());
    }

    private void awaitWinningRecord() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (winningRecordMapper.countByAPId(activityId, prizeId) == 1) {
                return;
            }
            Thread.sleep(100);
        }
        fail("10 秒内未生成中奖记录；请确认 RabbitMQ 已启动，并检查消费者日志");
    }

    private void awaitOutboxDone() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (notificationOutboxMapper.countByActivityPrizeAndStatus(
                    activityId, prizeId, NotificationOutboxStatusEnum.DONE.name()) == 2) {
                return;
            }
            Thread.sleep(100);
        }
        fail("10 秒内通知任务未完成；请检查通知消费者、短信/邮件配置和 RabbitMQ 日志");
    }

    private void removePreviousTestData() {
        List<Long> previousActivityIds = jdbcTemplate.queryForList(
                "select id from activity where activity_name = ?", Long.class, TEST_ACTIVITY_NAME);
        for (Long previousActivityId : previousActivityIds) {
            jdbcTemplate.update("delete from notification_outbox where activity_id = ?", previousActivityId);
            jdbcTemplate.update("delete from winning_record where activity_id = ?", previousActivityId);
            jdbcTemplate.update("delete from activity_user where activity_id = ?", previousActivityId);
            jdbcTemplate.update("delete from activity_prize where activity_id = ?", previousActivityId);
            jdbcTemplate.update("delete from activity where id = ?", previousActivityId);
        }
        jdbcTemplate.update("delete from prize where name = ?", TEST_PRIZE_NAME);
        jdbcTemplate.update("delete from `user` where email = ?", TEST_EMAIL);
    }
}
