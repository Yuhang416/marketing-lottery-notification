package com.example.lotterysystem.repro;

import com.example.lotterysystem.controller.param.DrawPrizeParam;
import com.example.lotterysystem.dao.dataobject.ActivityDO;
import com.example.lotterysystem.dao.dataobject.ActivityPrizeDO;
import com.example.lotterysystem.dao.dataobject.ActivityUserDO;
import com.example.lotterysystem.dao.dataobject.Encrypt;
import com.example.lotterysystem.dao.dataobject.NotificationOutboxDO;
import com.example.lotterysystem.dao.dataobject.PrizeDO;
import com.example.lotterysystem.dao.dataobject.UserDO;
import com.example.lotterysystem.dao.dataobject.WinningRecordDO;
import com.example.lotterysystem.dao.mapper.ActivityMapper;
import com.example.lotterysystem.dao.mapper.ActivityPrizeMapper;
import com.example.lotterysystem.dao.mapper.ActivityUserMapper;
import com.example.lotterysystem.dao.mapper.NotificationOutboxMapper;
import com.example.lotterysystem.dao.mapper.PrizeMapper;
import com.example.lotterysystem.dao.mapper.UserMapper;
import com.example.lotterysystem.dao.mapper.WinningRecordMapper;
import com.example.lotterysystem.service.enums.ActivityPrizeStatusEnum;
import com.example.lotterysystem.service.enums.ActivityPrizeTiersEnum;
import com.example.lotterysystem.service.enums.ActivityStatusEnum;
import com.example.lotterysystem.service.enums.ActivityUserStatusEnum;
import com.example.lotterysystem.service.enums.UserIdentityEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 可靠性复现测试的公共部分：准备数据、等待状态稳定、输出统计。
 *
 * <p>复现测试连真实的 MySQL、Redis、RabbitMQ。修复前只做统计、不做断言；
 * 对应的问题修复后，在测试里加上验收断言。
 * 统计行以 [REPRO] 开头，同时追加到 target/repro/{测试名}.txt。
 * 测试数据都带 REPRO- 标记，cleanupReproData() 只删带标记的数据。</p>
 */
@SpringBootTest
@ActiveProfiles({"local", "repro"})
@Tag("repro")
abstract class ReproSupport {

    static final Path REPORT_DIR = Path.of("target", "repro");
    private static final String MARK = "REPRO-";
    private static final Set<RabbitTemplate> RETURNS_REGISTERED =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static volatile Consumer<ReturnedMessage> returnsListener = returned -> { };

    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected RabbitAdmin rabbitAdmin;
    @Autowired
    protected RabbitTemplate rabbitTemplate;
    @Autowired
    protected ActivityMapper activityMapper;
    @Autowired
    protected PrizeMapper prizeMapper;
    @Autowired
    protected UserMapper userMapper;
    @Autowired
    protected ActivityPrizeMapper activityPrizeMapper;
    @Autowired
    protected ActivityUserMapper activityUserMapper;
    @Autowired
    protected WinningRecordMapper winningRecordMapper;
    @Autowired
    protected NotificationOutboxMapper notificationOutboxMapper;

    /**
     * 一个 RabbitTemplate 只能设置一个 ReturnsCallback，业务代码没有设置，
     * 这里设置一次，测试通过 onReturned() 切换监听逻辑。
     */
    @BeforeEach
    void registerReturnsCallback() {
        if (RETURNS_REGISTERED.add(rabbitTemplate)) {
            rabbitTemplate.setReturnsCallback(returned -> returnsListener.accept(returned));
        }
    }

    protected static void onReturned(Consumer<ReturnedMessage> listener) {
        returnsListener = listener == null ? returned -> { } : listener;
    }

    // ---------- 测试数据 ----------

    /** 一个活动、一个奖品（数量 1）、一个参与人，状态都是初始状态。 */
    protected record DrawFixture(Long activityId, Long prizeId, Long userId,
                                 String userName, String phone, String email) {

        DrawPrizeParam param() {
            DrawPrizeParam param = new DrawPrizeParam();
            param.setActivityId(activityId);
            param.setPrizeId(prizeId);
            param.setWinningTime(new Date());
            DrawPrizeParam.Winner winner = new DrawPrizeParam.Winner();
            winner.setUserId(userId);
            winner.setUserName(userName);
            param.setWinnerList(List.of(winner));
            return param;
        }
    }

    protected DrawFixture createDrawFixture(String tag) {
        String suffix = tag + "-" + UUID.randomUUID().toString().substring(0, 8);

        ActivityDO activity = new ActivityDO();
        activity.setActivityName(MARK + suffix);
        activity.setDescription("可靠性复现测试自动创建");
        activity.setStatus(ActivityStatusEnum.RUNNING.name());
        activityMapper.insert(activity);

        PrizeDO prize = new PrizeDO();
        prize.setName(MARK + suffix);
        prize.setDescription("可靠性复现测试自动创建");
        prize.setPrice(BigDecimal.ONE);
        prize.setImageUrl("/images/repro.png");
        prizeMapper.insert(prize);

        String phone = "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        String email = "repro-" + suffix.toLowerCase() + "@local.test";
        UserDO user = new UserDO();
        user.setUserName("复现用户-" + suffix);
        user.setEmail(email);
        user.setPhoneNumber(new Encrypt(phone));
        user.setPassword("repro-only");
        user.setIdentity(UserIdentityEnum.NORMAL.name());
        userMapper.insert(user);

        ActivityPrizeDO activityPrize = new ActivityPrizeDO();
        activityPrize.setActivityId(activity.getId());
        activityPrize.setPrizeId(prize.getId());
        activityPrize.setPrizeAmount(1L);
        activityPrize.setPrizeTiers(ActivityPrizeTiersEnum.FIRST_PRIZE.name());
        activityPrize.setStatus(ActivityPrizeStatusEnum.INIT.name());
        activityPrizeMapper.batchInsert(List.of(activityPrize));

        ActivityUserDO activityUser = new ActivityUserDO();
        activityUser.setActivityId(activity.getId());
        activityUser.setUserId(user.getId());
        activityUser.setUserName(user.getUserName());
        activityUser.setStatus(ActivityUserStatusEnum.INIT.name());
        activityUserMapper.batchInsert(List.of(activityUser));

        return new DrawFixture(activity.getId(), prize.getId(), user.getId(),
                user.getUserName(), phone, email);
    }

    /** 直接写一条中奖记录，用于只测通知链路的场景。 */
    protected void insertWinningRecord(DrawFixture f) {
        WinningRecordDO record = new WinningRecordDO();
        record.setActivityId(f.activityId());
        record.setActivityName("复现活动");
        record.setPrizeId(f.prizeId());
        record.setPrizeName("复现奖品");
        record.setPrizeTier(ActivityPrizeTiersEnum.FIRST_PRIZE.name());
        record.setWinnerId(f.userId());
        record.setWinnerName(f.userName());
        record.setWinnerEmail(f.email());
        record.setWinnerPhoneNumber(new Encrypt(f.phone()));
        record.setWinningTime(new Date());
        winningRecordMapper.batchInsert(List.of(record));
    }

    /** 直接写一条通知任务，返回 notificationId。 */
    protected String insertTask(DrawFixture f, Long winnerId, String channel, String status) {
        NotificationOutboxDO task = new NotificationOutboxDO();
        task.setNotificationId(UUID.randomUUID().toString());
        task.setActivityId(f.activityId());
        task.setPrizeId(f.prizeId());
        task.setWinnerId(winnerId);
        task.setChannel(channel);
        task.setStatus(status);
        task.setAttemptCount(0);
        task.setNextRetryTime(new Date());
        notificationOutboxMapper.batchInsert(List.of(task));
        return task.getNotificationId();
    }

    /** 只删除带 REPRO- 标记的测试数据。 */
    protected void cleanupReproData() {
        List<Long> activityIds = jdbcTemplate.queryForList(
                "select id from activity where activity_name like 'REPRO-%'", Long.class);
        for (Long id : activityIds) {
            jdbcTemplate.update("delete from notification_outbox where activity_id = ?", id);
            jdbcTemplate.update("delete from winning_record where activity_id = ?", id);
            jdbcTemplate.update("delete from activity_user where activity_id = ?", id);
            jdbcTemplate.update("delete from activity_prize where activity_id = ?", id);
            jdbcTemplate.update("delete from activity where id = ?", id);
        }
        jdbcTemplate.update("delete from prize where name like 'REPRO-%'");
        jdbcTemplate.update("delete from `user` where email like 'repro-%@local.test'");
    }

    // ---------- 查询 ----------

    protected String prizeStatus(DrawFixture f) {
        return jdbcTemplate.queryForObject(
                "select status from activity_prize where activity_id = ? and prize_id = ?",
                String.class, f.activityId(), f.prizeId());
    }

    protected String activityStatus(DrawFixture f) {
        return jdbcTemplate.queryForObject(
                "select status from activity where id = ?", String.class, f.activityId());
    }

    protected int recordCount(DrawFixture f) {
        return winningRecordMapper.countByAPId(f.activityId(), f.prizeId());
    }

    protected int outboxCount(DrawFixture f) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "select count(1) from notification_outbox where activity_id = ? and prize_id = ?",
                Integer.class, f.activityId(), f.prizeId()));
    }

    protected Map<String, Integer> outboxStatusCounts(DrawFixture f) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbcTemplate.query("select status, count(1) c from notification_outbox"
                        + " where activity_id = ? and prize_id = ? group by status order by status",
                rs -> {
                    counts.put(rs.getString("status"), rs.getInt("c"));
                }, f.activityId(), f.prizeId());
        return counts;
    }

    protected String taskStatus(String notificationId) {
        return jdbcTemplate.queryForObject(
                "select status from notification_outbox where notification_id = ?",
                String.class, notificationId);
    }

    /** 队列里待消费（ready）的消息数，不含已投递未确认的。 */
    protected int queueCount(String queueName) {
        QueueInformation info = rabbitAdmin.getQueueInfo(queueName);
        return info == null ? -1 : info.getMessageCount();
    }

    // ---------- 等待和输出 ----------

    /**
     * 每 200ms 取一次快照，连续 stableMs 没有变化就认为处理结束；最多等 maxMs。
     * stableMs 要大于消费端重试的总时长，否则会在两次重试之间误判为结束。
     */
    protected <T> T awaitStable(Supplier<T> snapshot, long stableMs, long maxMs) {
        long start = System.currentTimeMillis();
        long lastChange = start;
        T last = snapshot.get();
        while (System.currentTimeMillis() - start < maxMs) {
            sleep(200);
            T current = snapshot.get();
            if (!Objects.equals(current, last)) {
                last = current;
                lastChange = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastChange >= stableMs) {
                return current;
            }
        }
        System.out.println("[REPRO] 警告：" + maxMs + "ms 内状态没有稳定，按当前状态统计");
        return last;
    }

    protected static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected static void startReport(String name, String params) {
        report(name, "");
        report(name, "==== " + name + " " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " " + params);
    }

    protected static void report(String name, String line) {
        System.out.println("[REPRO] " + line);
        try {
            Files.createDirectories(REPORT_DIR);
            Files.writeString(REPORT_DIR.resolve(name + ".txt"), line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
