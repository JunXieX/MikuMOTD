#!/usr/bin/env bash
# 三场景 MOTD 性能对比：裸代理 / 事件模式兜底 / 字节级快速路径。
# 场景交替轮测以抵消共享 runner 的负载漂移，结果以中位数写入 job summary。
set -euo pipefail

DIR=bench-run
VELOCITY_JAR=$DIR/velocity.jar
PLUGIN_JAR=$(ls build/libs/MikuMOTD-*.jar)
PORT=25565
ROUNDS=${BENCH_ROUNDS:-3}
LATENCY_RUNS=${BENCH_LATENCY_RUNS:-2000}
BENCH_SECONDS=${BENCH_SECONDS:-6}
BENCH_THREADS=${BENCH_THREADS:-8}
RESULTS=$DIR/results.txt
SUMMARY=$DIR/summary.md

ROOT=$PWD
WORK=$ROOT/$DIR
mkdir -p "$WORK"
: > "$RESULTS"

# 代理与压测客户端统一使用工作流指定的 JDK，避免 runner 默认 PATH 上存在旧版本 java
JCMD="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JCMD" ]; then
  echo "JAVA_HOME 未指向可用的 JDK: $JAVA_HOME" >&2
  exit 1
fi

"${JAVA_HOME}/bin/javac" -d "$WORK" bench/MotdBench.java

# 场景目录：bare=无插件；compat=事件模式；fast=快速路径。
# compat/fast 使用同一份精简配置（无图标、短描述），排除载荷大小差异。
make_plugins() {
  local target=$1 mode=$2
  rm -rf "$target"
  mkdir -p "$target/mikumotd"
  if [ "$mode" != "bare" ]; then
    cp "$PLUGIN_JAR" "$target/"
    cat > "$target/mikumotd/config.conf" <<CONF
general {
    update-interval-ms=3000
    direct-write=true
    compat-mode=$([ "$mode" = "compat" ] && echo true || echo false)
    text-format=LEGACY_AMPERSAND
    png-quality=-1
}
players {
    max-count-type=FIXED
    max-count=1000
    fake-online-fixed=0
    fake-online-percent=0
}
motd {
    version-name="MikuMOTD"
    descriptions=["&aBenchmark MOTD line one"]
    favicons=[]
    player-list=[]
}
CONF
  fi
}

start_proxy() {
  local name=$1 mode=$2
  local run_dir=$WORK/run-$name
  make_plugins "$WORK/plugins-$name" "$mode"
  mkdir -p "$run_dir/plugins"
  cp -r "$WORK/plugins-$name/." "$run_dir/plugins/"

  local log=$WORK/$name-server.log
  : > "$log"
  (cd "$run_dir" && "$JCMD" -Xms512m -Xmx512m -jar "$WORK/velocity.jar" > "$log" 2>&1 & echo $! > "$WORK/$name.pid")

  for _ in $(seq 1 90); do
    if grep -q "Done" "$log" 2>/dev/null; then
      return 0
    fi
    if ! kill -0 "$(cat "$WORK/$name.pid")" 2>/dev/null; then
      echo "代理进程提前退出: $name" >&2
      tail -20 "$log" >&2 || true
      return 1
    fi
    sleep 1
  done
  echo "代理启动超时: $name" >&2
  tail -20 "$log" >&2 || true
  return 1
}

stop_proxy() {
  local name=$1
  if [ -f "$WORK/$name.pid" ]; then
    local pid
    pid=$(cat "$WORK/$name.pid")
    kill "$pid" 2>/dev/null || true
    sleep 2
    # Velocity 的优雅关闭可能等待内部任务超过可接受时长，基准切换场景直接强杀
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$WORK/$name.pid"
  fi
  pkill -9 -f "velocity.jar" 2>/dev/null || true
  # 等端口释放，避免影响下一场景
  for _ in $(seq 1 30); do
    if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
      exec 3>&- 3<&- || true
      sleep 1
    else
      break
    fi
  done
  sleep 1
}

measure() {
  local name=$1 round=$2
  local lat qps fail
  lat=$("$JCMD" -cp "$WORK" MotdBench 127.0.0.1 "$PORT" --latency "$LATENCY_RUNS" | awk '{print $2}')
  read -r _ qps _ fail <<< "$("$JCMD" -cp "$WORK" MotdBench 127.0.0.1 "$PORT" --bench "$BENCH_SECONDS" --threads "$BENCH_THREADS")"
  echo "$name $round $lat $qps $fail" >> "$RESULTS"
  echo "  第 $round 轮：延迟 ${lat}µs，QPS $qps（失败 $fail）"
}

echo "== 场景交替测量（共 $ROUNDS 轮）=="
for round in $(seq 1 "$ROUNDS"); do
  for scenario in bare compat fast; do
    start_proxy "$scenario" "$scenario"
    echo "[$scenario 第 $round 轮]"
    measure "$scenario" "$round"
    stop_proxy "$scenario"
  done
done

# 汇总（中位数）
median() {
  printf '%s\n' "$@" | sort -g | awk '{a[NR]=$1} END {if (NR % 2) print a[(NR+1)/2]; else print (a[NR/2]+a[NR/2+1])/2}'
}

{
  echo "## MOTD 性能对比（runner 内自连，$ROUNDS 轮中位数）"
  echo
  echo "| 场景 | 平均延迟（µs/完整 ping） | QPS（${BENCH_THREADS} 线程 × ${BENCH_SECONDS}s） | 最大失败数 |"
  echo "|---|---|---|---|"
  for scenario in bare compat fast; do
    case $scenario in
      bare)  label="裸代理（无插件）" ;;
      compat) label="MikuMOTD 事件模式" ;;
      fast)  label="MikuMOTD 快速路径" ;;
    esac
    lats=$(awk -v s="$scenario" '$1==s {print $3}' "$RESULTS")
    qpss=$(awk -v s="$scenario" '$1==s {print $4}' "$RESULTS")
    fails=$(awk -v s="$scenario" '$1==s {print $5}' "$RESULTS" | sort -g | tail -1)
    printf '| %s | %s | %s | %s |\n' "$label" "$(median $lats)" "$(median $qpss)" "${fails:-0}"
  done
  echo
  echo "> 共享 runner 上负载有漂移，本表仅供同批次内横向对比，绝对数值不代表生产环境。"
} > "$SUMMARY"

cat "$SUMMARY"
