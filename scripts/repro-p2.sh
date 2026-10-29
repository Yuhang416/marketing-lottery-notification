#!/usr/bin/env bash
# P2 开奖进行到一半时进程退出：
#   第一次运行：状态流转之后、写中奖记录之前 halt(1)，模拟进程被直接杀掉；
#   第二次运行：重新启动，开奖消息会被重新投递，看最后有没有中奖记录和通知任务。
# 第一次运行时 Maven 会报 "The forked VM terminated"，这是预期的。
#
# 用法：MYSQL_PASSWORD=*** scripts/repro-p2.sh
# 依赖：本机 MySQL、Redis、RabbitMQ（含管理插件，账号默认 guest/guest），JDK 17。
set -u
cd "$(dirname "$0")/.."

MVN="./mvnw -o -Prepro"
REPORT=target/repro/P2.txt
RABBIT_API="${RABBITMQ_API:-http://127.0.0.1:15672/api}"
RABBIT_USER="${RABBITMQ_USERNAME:-guest}:${RABBITMQ_PASSWORD:-guest}"

$MVN -q test-compile || exit 1

echo "== 第一次运行：开奖进行到一半时进程退出"
$MVN surefire:test -Dtest='P2CrashBetweenTransactionsTest#crashAfterFirstTransaction' > target/repro/p2-crash.log 2>&1
echo "Maven 退出码 $?（非 0 是预期的）"

echo "== 两次运行之间的状态"
sleep 6
ACTIVITY_ID=$(grep '^activityId=' target/repro/p2-fixture.properties | cut -d= -f2)
PRIZE_ID=$(grep '^prizeId=' target/repro/p2-fixture.properties | cut -d= -f2)
STATE=$(MYSQL_PWD="${MYSQL_PASSWORD:-}" mysql -h"${MYSQL_HOST:-127.0.0.1}" -u"${MYSQL_USERNAME:-root}" -N lottery_system -e "
  select concat('奖品=', (select status from activity_prize where activity_id = $ACTIVITY_ID and prize_id = $PRIZE_ID),
                '，活动=', (select status from activity where id = $ACTIVITY_ID),
                '，中奖记录=', (select count(1) from winning_record where activity_id = $ACTIVITY_ID),
                '，通知任务=', (select count(1) from notification_outbox where activity_id = $ACTIVITY_ID))" 2>/dev/null)
QUEUE=$(curl -s -u "$RABBIT_USER" "$RABBIT_API/queues/%2F/DirectQueue" | python3 -I -c "import json,sys; print(json.load(sys.stdin).get('messages'))")
LINE="进程退出后、重启前：$STATE，开奖队列里待处理的消息=$QUEUE"
echo "[REPRO] $LINE"
echo "$LINE" >> "$REPORT"

echo "== 第二次运行：重新启动"
$MVN surefire:test -Dtest='P2CrashBetweenTransactionsTest#recoverAfterRestart' > target/repro/p2-recover.log 2>&1
echo "Maven 退出码 $?"

echo "== 本次记录（$REPORT）"
tail -n 4 "$REPORT"
