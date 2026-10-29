#!/usr/bin/env bash
# T3 双实例投递：两个 JVM 在同一个时间点各扫描一次 PENDING 任务，统计每个任务被投递了几次。
#   seed  ：写入 TASKS 条 PENDING 任务；
#   scan  ：两个 JVM 同时启动，到约定时间点各执行一次投递扫描（消费者和自动扫描都已关闭）；
#   count ：读空通知队列，按 notificationId 统计投递次数。
#
# 用法：MYSQL_PASSWORD=*** TASKS=1000 scripts/repro-t3.sh
# 依赖：本机 MySQL、Redis、RabbitMQ，JDK 17。
set -u
cd "$(dirname "$0")/.."

TASKS="${TASKS:-1000}"
MVN="./mvnw -o -Prepro"
TEST="-Dtest=T3DuplicatePublishTest"
REPORT=target/repro/T3.txt
mkdir -p target/repro

$MVN -q test-compile || exit 1
echo "" >> "$REPORT"
echo "==== T3 $(date '+%Y-%m-%d %H:%M:%S') tasks=$TASKS 实例数=2 每个实例扫描一次、批大小=$TASKS" >> "$REPORT"

echo "== seed：写入 $TASKS 条 PENDING 任务"
$MVN surefire:test $TEST -Drole=seed -Dtasks="$TASKS" > target/repro/t3-seed.log 2>&1 || { echo "seed 失败，见 target/repro/t3-seed.log"; exit 1; }

# 留 60 秒给两个 JVM 启动，到点后同时扫描
FIRE_AT=$(( ($(date +%s) + 60) * 1000 ))
echo "== scan：两个实例将在 $(date -r $((FIRE_AT / 1000)) '+%H:%M:%S') 同时扫描"
$MVN surefire:test $TEST -Drole=scan -Dinstance=A -DfireAt="$FIRE_AT" -Dnotification.outbox.batch-size="$TASKS" > target/repro/t3-scan-A.log 2>&1 &
PID_A=$!
$MVN surefire:test $TEST -Drole=scan -Dinstance=B -DfireAt="$FIRE_AT" -Dnotification.outbox.batch-size="$TASKS" > target/repro/t3-scan-B.log 2>&1 &
PID_B=$!
wait $PID_A $PID_B

echo "== count：统计通知队列"
$MVN surefire:test $TEST -Drole=count > target/repro/t3-count.log 2>&1 || { echo "count 失败，见 target/repro/t3-count.log"; exit 1; }

echo "== 本次记录（$REPORT）"
awk '/^==== T3/{buf=""} {buf=buf $0 "\n"} END{printf "%s", buf}' "$REPORT"
