# 营销活动开奖与异步通知系统

基于 Spring Boot 的运营活动开奖系统。运营人员提交某个奖项对应的固定中奖名单后，系统异步完成开奖结算、中奖记录生成，以及短信/邮件通知。


## 技术栈

- Java 17、Spring Boot 3、MyBatis、MySQL
- Redis
- RabbitMQ、Spring AMQP
- JUnit 5

## 业务流程

```text
运营端提交 activityId + prizeId + 固定中奖名单
        │
        ▼
RabbitMQ 主开奖队列
        │
        ▼
MqReceiver
  ├─ 参数与业务状态校验
  ├─ 两阶段状态流转
  │    先处理参与关系、活动奖品，再处理活动状态
  └─ 写入中奖记录和通知 Outbox
        │
        ▼
MySQL 本地事务提交
        │
        ▼
NotificationOutboxPublisher 定时扫描 PENDING 任务
        │
        ▼
RabbitMQ 通知队列
        │
        ▼
NotificationReceiver 回查中奖记录并发送短信/邮件
        │
        ▼
通知任务更新为 DONE
```

## 核心设计

### 1. 开奖状态流转与异常补偿

开奖状态按依赖关系拆为两个阶段：先处理参与关系与活动奖品，再处理活动状态。

当前基础流程由多个局部事务组成。后续结算异常时，消费者会根据当前业务状态执行逆向补偿，将状态恢复至可重试状态，并继续抛出异常触发消息监听器重试；同一份开奖名单不会要求前端重新圈选。

这是一种借鉴 Saga 补偿思想的本地工作流处理，不是完整的分布式 Saga 实现。它主要处理普通运行时异常；极端宕机、补偿失败和跨 MySQL/Redis/MQ 的全局一致性仍是后续可演进边界。

### 2. Transactional Outbox

中奖记录与通知任务必须同时存在。若先写中奖记录、再直接发送 MQ，应用可能在两步之间崩溃，造成“中奖已确认但通知任务丢失”。

`saveWinnerRecords()` 在同一个 MySQL 事务中批量写入：

```text
WinningRecord
+ notification_outbox（每位中奖者生成 SMS、MAIL 两条 PENDING 任务）
```

两者要么一起提交，要么一起回滚。提交后的 `PENDING` 任务由后台投递器继续处理，即使应用重启也可恢复扫描。

### 3. 通知投递状态机

通知任务状态：

```text
PENDING → PUBLISHED → DONE
                 └──→ CANCELLED
```

- `PENDING`：已持久化，等待投递确认。
- `PUBLISHED`：RabbitMQ 已确认接收。
- `DONE`：短信或邮件工具明确返回成功。
- `CANCELLED`：回查不到对应中奖记录，不再继续发送。

投递器每 3 秒扫描最多 20 条 `PENDING` 任务，并为每条消息携带 `notificationId` 作为关联标识。Broker Confirm 成功且消息未被退回后，才将任务推进为 `PUBLISHED`。

消费者允许处理 `PENDING` 与 `PUBLISHED`：消息可能先到消费者，发布确认回调才稍后到达。`DONE`、`CANCELLED` 属于终态，重复消息会被直接跳过。

关键状态推进使用带前置状态的条件更新。例如迟到 Confirm 只能推进 `PENDING → PUBLISHED`，不会覆盖已经完成的 `DONE`：

```sql
UPDATE notification_outbox
SET status = 'PUBLISHED'
WHERE notification_id = ?
  AND status = 'PENDING';
```

该链路提供的是从 MySQL 到 RabbitMQ 的**至少一次**可靠投递。若 Broker 已收到消息但应用在写入 `PUBLISHED` 前崩溃，任务仍为 `PENDING`，重启后会再次投递；因此消费者保留基础防重逻辑。外部短信/邮件调用不承诺 Exactly Once。

### 4. 主开奖消息失败处理

主开奖监听器使用自动确认模式。业务处理发生异常时：

```text
执行逆向补偿
→ 抛出异常
→ Spring Rabbit 监听器对同一条消息重试，最多 5 次
→ 重试耗尽后进入持久化 DLQ
```

DLQ 作为故障消息停车场，不自动回灌主队列。修复问题后可通过 RabbitMQ 管理台人工重放消息，避免毒消息反复重试。

### 5. 缓存

Redis 缓存活动详情和中奖记录。缓存读取异常或未命中时回源 MySQL；缓存不作为开奖结算的唯一数据来源。

## 本地运行

### 前置条件

- JDK 17
- Maven Wrapper（仓库内已包含）
- MySQL 8+，创建数据库 `lottery_system`
- Redis
- RabbitMQ（默认端口 `5672`，管理台通常为 `15672`）

> 基础活动业务表（如 `activity`、`activity_prize`、`activity_user`、`winning_record` 等）沿用课程基础项目的 Schema。本仓库新增的 Outbox 表脚本在 `sql/V1__create_notification_outbox.sql`。

### 1. 创建 Outbox 表

```bash
mysql -uroot -p lottery_system < sql/V1__create_notification_outbox.sql
```

### 2. 配置环境变量

本地默认启用 `local` Profile，配置见 `src/main/resources/application-local.properties`。

```bash
export MYSQL_PASSWORD=root
export RABBITMQ_USERNAME=guest
export RABBITMQ_PASSWORD=guest
```

短信/邮件默认启用 mock，不调用真实第三方服务；通知内容会写入应用日志。

### 3. 启动应用

```bash
./mvnw spring-boot:run
```

## 集成测试

`DrawPrizeTest` 会创建一套独立测试数据，发送开奖命令，并验证：

```text
中奖记录生成
→ 每位中奖者生成 SMS / MAIL 两条 Outbox 任务
→ 任务经通知队列消费后均到达 DONE
```

运行前请确保 MySQL、Redis、RabbitMQ 均已启动：

```bash
MYSQL_PASSWORD=root ./mvnw -Dmaven.test.skip=false \
  -Dmaven.compiler.useIncrementalCompilation=false \
  -Dtest=DrawPrizeTest test
```

## 当前边界与后续演进

- 通知投递采用至少一次语义；要进一步降低外部短信重复发送，需要将 `notificationId` 作为三方服务业务幂等键。
- 当前主开奖生产端仍是直接投递，尚未引入持久化 `drawId` 或开奖命令 Outbox；若业务要求命令绝不丢失，可增加命令持久化、生产端 Confirm 与消费幂等。
- 当前 Outbox 投递器为单实例轻量轮询模型；多实例场景可增加 `SENDING`、租约和超时回收，避免多个实例同时投递同一任务。

