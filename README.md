# 营销活动开奖与异步通知系统

基于 Spring Boot 的运营活动开奖系统。运营人员提交某个奖项对应的固定中奖名单后，系统异步完成开奖结算、中奖记录生成，以及短信/邮件通知。系统支持部署多个实例（为了发版、宕机时服务不中断），所以重点处理了重复消息、多实例并发和各种故障下的数据正确性。

每个可靠性问题都先写测试复现、记录修复前的数据，修复后再跑一遍对比，数据见 [docs/test-results.md](docs/test-results.md)。

## 技术栈

- Java 17、Spring Boot 3、MyBatis、MySQL 8
- Redis
- RabbitMQ、Spring AMQP（Publisher Confirm、死信队列）
- JUnit 5、Docker Compose

## 业务流程

```text
运营提交 activityId + prizeId + 中奖名单
        │
        ▼
开奖接口：发消息到开奖队列，等 Broker 确认消息进入队列后才返回成功
        │
        ▼
开奖消费者 MqReceiver → DrawSettlementService.settle()，一个本地事务：
  ├─ update activity_prize ... where status = 'INIT'  抢占奖品，影响 0 行直接返回
  ├─ 人员、活动状态流转
  └─ 写中奖记录 + 写通知任务（notification_outbox，每位中奖者 SMS、MAIL 各一条）
        │  提交后写 Redis 缓存
        ▼
Outbox 投递器（多实例同时运行）
  ├─ select ... for update skip locked 领取到期任务，推后 next_retry_time 作为租约
  └─ 提交后投递到通知队列，Confirm 成功改为 PUBLISHED
        │
        ▼
通知消费者 NotificationReceiver
  ├─ update ... set status = 'SENDING' 抢占发送权，抢不到直接返回
  ├─ 回查中奖记录，发送短信/邮件（notificationId 作为短信平台的 OutId）
  └─ 改为 DONE
        │
        ▼  重试耗尽
死信队列 → 死信消费者：落库、ERROR 告警，人工排查后调用接口重放
```

## 核心设计

### 1. 开奖结算：一个本地事务 + 条件更新

早期版本把「状态流转」和「写中奖记录」拆成两个事务，失败时靠逆向补偿把状态改回去、把中奖记录删掉。测试发现两个问题：

- 同一个奖品的两条开奖消息同时处理时，后一条撞上中奖记录的唯一索引进入补偿，**把前一条已经成功的开奖撤销了**（100 轮测试 100 轮出现）；
- 两个事务之间进程退出，补偿没有机会执行，留下「奖品已开奖、但没有中奖记录」的状态，开奖永久丢失。

现在合成一个事务，第一步执行：

```sql
update activity_prize set status = 'COMPLETED'
 where activity_id = ? and prize_id = ? and status = 'INIT'
```

两个事务同时执行时只有一个能改成功，另一个被行锁挡住，等前一个提交后再执行，读到的已经是 COMPLETED（update 是当前读），影响 0 行直接返回。失败时整个事务回滚，不再需要补偿。中奖记录表的唯一索引 `(winner_id, activity_id, prize_id)` 作为兜底，撞上唯一键时在事务外面捕获，视为已处理。

### 2. 开奖入口：等 Publisher Confirm

`convertAndSend` 返回只代表消息交给了客户端，不代表进了队列。开奖接口发送时带上 `CorrelationData`，最多等 3 秒：**ack 并且消息没有被退回**才返回成功，否则返回失败让运营重试。路由失败时 Broker 返回的是 `ack=true` 加退回，所以必须同时检查退回。重试产生的重复消息由第 1 点的条件更新挡住。

### 3. Transactional Outbox：多实例领取、退避和重试上限

中奖记录和通知任务在同一个事务里写入，两者要么都有，要么都没有。投递器把任务发到通知队列：

- **领取**：在一个短事务里 `select ... where status = 'PENDING' and next_retry_time <= ? for update skip locked`，同一事务里 `attempt_count + 1`，并把 `next_retry_time` 往后推，当作租约。两个实例同时领取拿到的一定是不同的任务；领走之后，别的实例在租约到期前扫描不到它。
- **投递**：提交后在事务外投递。Confirm 成功改为 PUBLISHED；失败只记录 `last_error`，租约到期后重新领取。实例领取后、投递前宕机，租约到期后由别的实例接手。
- **退避和上限**：租约依次是 10 秒、30 秒、1 分钟、2 分钟、5 分钟，加 ±20% 抖动；投递满 8 次改为 FAILED，打 ERROR 日志告警。

时间统一由应用计算：应用按 JDBC 的 `serverTimezone` 写入时间，数据库 `now()` 的时区可能不同，两者不能混用。

### 4. 通知消费：抢占发送权

同一个任务的消息可能被多个消费者同时处理。发送前先执行 `update ... set status = 'SENDING' where status in ('PENDING', 'PUBLISHED', 'FAILED')`，影响 0 行就不发，只有抢到的消费者能把任务改成 DONE。

- 发送失败时先把 SENDING 改回 PUBLISHED，再抛出异常，否则监听器在进程内重试时会抢不到发送权；
- SENDING 状态下 `next_retry_time` 当作发送租约（5 分钟），消费者抢到后崩溃，租约到期由定时任务改回 PENDING，重新投递。

发送端只能保证**至少一次**：发送成功后、改成 DONE 之前崩溃，会再发一次。短信把 `notificationId` 作为平台的 `OutId` 传过去，最终去重靠下游。

### 5. 死信：落库、告警、人工重放

开奖队列、通知队列都配置了死信队列。死信消费者只做两件事：把消息写进 `dead_letter_record`，打 ERROR 日志；通知类死信同时把任务标成 FAILED。死信消费者用单独的监听容器，转换器是 `SimpleMessageConverter`，不解析 JSON，格式错误的毒消息也能收下来。**不自动回灌**，毒消息自动发回原队列只会无限循环。排查修复后调用 `POST /dead-letters/{id}/replay`（需要登录）按原交换机和路由键重发，同一条记录只能重放一次。

### 6. 缓存

Redis 缓存活动详情和中奖记录，都在事务提交之后再写，事务回滚时缓存不会留下回滚前的数据。缓存读取异常或未命中时回源 MySQL。

### 通知任务状态

```text
PENDING ──领取投递、Confirm 成功──▶ PUBLISHED ──抢占发送权──▶ SENDING ──发送成功──▶ DONE
   │                                                          │
   │ 投递满 8 次                                       发送失败 ─┘ 改回 PUBLISHED，重试耗尽进入死信
   ▼                                                          │
 FAILED ◀────────────────── 进入死信 ──────────────────────────┘
   │  人工重放后可以重新抢占发送
回查不到中奖记录 ──▶ CANCELLED
```

## 可靠性测试

复现测试在 `src/test/java/com/example/lotterysystem/repro/`，连真实的 MySQL、Redis、RabbitMQ 运行；普通构建不会执行，用 `-Prepro` 单独运行。完整的环境、命令和原始数据见 [docs/test-results.md](docs/test-results.md)。

| 场景 | 修复前 | 修复后 |
|---|---|---|
| T1 同一奖品的两条开奖消息同时处理（100 轮） | 误撤销 100 次 | **0** |
| P2 开奖进行到一半时进程退出 | 开奖永久丢失 | 事务回滚，重启后**正常完成** |
| T2 开奖消息路由不到队列（3 次） | 接口返回成功 3 次 | **0**，如实返回失败 |
| T3 两个实例同时扫描 1000 条通知任务 | 重复投递 1000 条 | **0** |
| T4 永久失败的通知任务（60 秒） | 每 3 秒重投、不停止 | 按退避重试到上限，变为 **FAILED** 并告警 |
| T5 同一条通知消息同时到达两次（100 轮） | 99 轮发送 2 次 | **0** |
| T6 通知发送一直失败 | 消息被丢弃，任务卡在 PUBLISHED | **进入死信**、有记录和告警，重放后发出 |
| T7 实例领取任务后宕机 | — | 任务被另一个实例**接手**，每条只投递 1 次 |
| T8 消费者抢到发送权后崩溃 | — | 发送租约到期后**回收**、重新投递 |
| T9 格式错误的毒消息 | 死信消费者也转换失败，死信丢失 | **落库**，可以人工排查 |

运行方式（需要 JDK 17，数据库密码通过环境变量传入）：

```bash
MYSQL_PASSWORD=你的密码 ./mvnw test -Prepro -Dtest=T1DuplicateDrawTest -Drounds=100
MYSQL_PASSWORD=你的密码 scripts/repro-t3.sh   # 双实例投递，需要两个进程
```

其他场景的命令见 [docs/test-results.md](docs/test-results.md)。

## 本地运行

### 方式一：Docker Compose（双实例 + Nginx）

```bash
cp .env.example .env      # 填写 MySQL、RabbitMQ 的密码，.env 不会被提交
docker compose up -d --build
```

会启动 MySQL、Redis、RabbitMQ、两个应用实例 `app-a`、`app-b`，以及轮询转发到两个实例的 Nginx（`http://localhost:8080`）。MySQL 首次启动时按文件名顺序执行 `sql/` 下的建表语句。为了不和本机已有的服务冲突，容器端口映射到 13306（MySQL）、16379（Redis）、15673（RabbitMQ 管理台）。

已在 Docker 29.8 / Compose v5.5 上验证：MySQL 按顺序执行了 V0、V1、V2 三个建表脚本；两个实例都连上了 RabbitMQ，每个队列各有 2 个消费者；Nginx 轮询转发正常。往开奖队列同时发两条相同的开奖消息，两个实例在同一秒各收到一条：`app-a` 完成开奖，`app-b` 的条件更新影响 0 行后直接返回；最终 1 条中奖记录，SMS、MAIL 两条通知分别由两个实例发出，没有重复。

查看应用日志：local profile 在 `logback-spring.xml` 里没有配置输出，`docker compose logs` 看不到应用日志。排查时可以给两个实例加上环境变量 `SPRING_PROFILES_ACTIVE=local,dev`，借用 dev profile 的控制台日志配置（镜像里没有 `application-dev.properties`，不会加载别的配置）。

### 方式二：直接连本机的服务

前置条件：JDK 17（JDK 23 默认不运行注解处理器，Lombok 不会生效）、MySQL 8、Redis、RabbitMQ。

1. 建库建表，按顺序执行：

   ```bash
   mysql -uroot -p lottery_system < sql/V0__base_schema.sql
   mysql -uroot -p lottery_system < sql/V1__create_notification_outbox.sql
   mysql -uroot -p lottery_system < sql/V2__create_dead_letter_record.sql
   ```

2. 设置环境变量，配置见 `src/main/resources/application-local.properties`：

   ```bash
   export MYSQL_PASSWORD=你的密码
   export RABBITMQ_USERNAME=guest
   export RABBITMQ_PASSWORD=guest
   ```

3. 启动：`./mvnw spring-boot:run`

短信、邮件默认是 mock 模式，不调用真实的第三方服务，只写日志。

### 集成测试

`DrawPrizeTest` 走一遍单次开奖到通知发送的完整链路。pom 默认跳过测试，运行时要打开：

```bash
MYSQL_PASSWORD=你的密码 ./mvnw test -Dmaven.test.skip=false -Dtest=DrawPrizeTest
```

## 当前边界

- 通知只能保证至少一次。抢占发送权解决了并发重复，但发送成功后、结案前进程崩溃仍会再发一次，最终去重依赖短信平台按 `OutId` 处理；这一点在本地 mock 模式下没有验证。
- 死信消费者落库失败时（例如数据库不可用），消息重试后会被丢弃，只能从 ERROR 日志里找回消息内容。
- 投递失败后最快 10 秒重试；FAILED 的任务和死信需要人工处理。
- 已经存在的 RabbitMQ 队列不能直接增加死信参数。新环境由程序自动创建；已有环境建议用 policy 配置死信，不要删除队列。
- 应用写入的时间按 JDBC `serverTimezone=Asia/Shanghai` 换算，数据库默认值（如 `gmt_create`）使用数据库自己的时区，两者可能不一致。
