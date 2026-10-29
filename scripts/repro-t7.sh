#!/usr/bin/env bash
# T7 实例领取任务后宕机：
#   seed   ：写入 TASKS 条 PENDING 任务；
#   crash  ：一个进程领取任务（租约已写入并提交）后，在发出第一条消息前 halt(1)；
#   recover：另一个进程正常运行投递器，等租约到期后接手，统计每个任务被投递了几次。
# crash 那一步 Maven 会报 "The forked VM terminated"，这是预期的。
#
# 用法：MYSQL_PASSWORD=*** TASKS=20 scripts/repro-t7.sh
# 依赖：本机 MySQL、Redis、RabbitMQ，JDK 17。
set -u
cd "$(dirname "$0")/.."

TASKS="${TASKS:-20}"
MVN="./mvnw -o -Prepro"
TEST="-Dtest=T7InstanceCrashTest"
REPORT=target/repro/T7.txt
mkdir -p target/repro

$MVN -q test-compile || exit 1

echo "== seed：写入 $TASKS 条 PENDING 任务"
$MVN surefire:test $TEST -Drole=seed -Dtasks="$TASKS" > target/repro/t7-seed.log 2>&1 || { echo "seed 失败，见 target/repro/t7-seed.log"; exit 1; }

echo "== crash：领取任务后、投递前进程退出"
$MVN surefire:test $TEST -Drole=crash > target/repro/t7-crash.log 2>&1
echo "Maven 退出码 $?（非 0 是预期的）"

echo "== recover：另一个进程接手（打开自动扫描，每 3 秒一次）"
$MVN surefire:test $TEST -Drole=recover \
  -Dnotification.outbox.publish-interval-ms=3000 -Dnotification.outbox.publish-initial-delay-ms=0 \
  > target/repro/t7-recover.log 2>&1
echo "Maven 退出码 $?"
grep -E 'Tests run:.*Fail' target/repro/t7-recover.log | tail -1

echo "== 本次记录（$REPORT）"
awk '/^==== T7/{buf=""} {buf=buf $0 "\n"} END{printf "%s", buf}' "$REPORT"
