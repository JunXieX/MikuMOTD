#!/usr/bin/env bash
# MOTD 插件性能对比：裸代理 / MiniMOTD / FastMOTD / MikuMOTD 事件模式 / MikuMOTD 快速路径。
# 场景交替轮测以抵消共享 runner 的负载漂移，结果以中位数写入 job summary。
# 第三方插件在现场从源码构建，构建失败的场景自动跳过，不影响其余场景。
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
mkdir -p "$WORK"
"${JAVA_HOME}/bin/javac" -d "$WORK" bench/MotdBench.java

declare -A SKIPPED

# ---------------------------------------------------------------------------
# 第三方插件构建（构建失败仅跳过对应场景）
# ---------------------------------------------------------------------------

build_fastmotd() {
  local src=$WORK/src-FastMOTD
  git clone --depth 1 https://github.com/Elytrium/FastMOTD "$src" >/dev/null 2>&1
  (cd "$src" && ./gradlew build -x test --no-daemon -q) >&2
  local jar
  jar=$(find "$src/build/libs" -name "*.jar" ! -name "*-sources.jar" | head -1)
  [ -n "$jar" ]
  echo "$jar"
}

build_minimotd() {
  local src=$WORK/src-MiniMOTD
  git clone --depth 1 https://github.com/jpenilla/MiniMOTD "$src" >/dev/null 2>&1
  (cd "$src" && ./gradlew build -x test --no-daemon -q) >&2
  local jar
  jar=$(find "$src" -path "*/build/libs/*" -name "*.jar" ! -name "*-sources.jar" | grep -i velocity | head -1)
  [ -n "$jar" ]
  echo "$jar"
}

echo "== 构建第三方插件 =="
FASTMOTD_JAR=""
if build_fastmotd > "$WORK/fastmotd-jar.txt"; then
  FASTMOTD_JAR=$(cat "$WORK/fastmotd-jar.txt")
  echo "FastMOTD 构建成功：$FASTMOTD_JAR"
else
  SKIPPED[fastmotd]="源码构建失败"
  echo "FastMOTD 构建失败，将跳过该场景"
fi

MINIMOTD_JAR=""
if build_minimotd > "$WORK/minimotd-jar.txt"; then
  MINIMOTD_JAR=$(cat "$WORK/minimotd-jar.txt")
  echo "MiniMOTD 构建成功：$MINIMOTD_JAR"
else
  SKIPPED[minimotd]="源码构建失败"
  echo "MiniMOTD 构建失败，将跳过该场景"
fi

# ---------------------------------------------------------------------------
# 场景准备
# ---------------------------------------------------------------------------

# 场景目录：bare=无插件；minimotd/fastmotd=第三方；compat=事件模式；fast=快速路径。
# 所有带插件场景使用对齐的精简 MOTD（单行描述、无图标、无玩家列表、固定 1000 上限），
# 排除载荷与功能差异，只对比处理路径开销。
make_plugins() {
  local target=$1 mode=$2
  rm -rf "$target"
  mkdir -p "$target/mikumotd" "$target/fastmotd" "$target/minimotd"
  case $mode in
    bare)
      ;;
    compat | fast)
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
    version-name="Benchmark"
    descriptions=["&aBenchmark MOTD line one"]
    favicons=[]
    player-list=[]
}
CONF
      ;;
    fastmotd)
      # 不预置配置：FastMOTD 的自制 YAML 序列化器对手写 inline 空 map 解析异常，
      # 首次启动由其自行生成默认配置（与 MiniMOTD 场景同为默认配置口径）
      cp "$FASTMOTD_JAR" "$target/"
      ;;
    minimotd)
      cp "$MINIMOTD_JAR" "$target/"
      ;;
  esac
}

# 各插件在服务器日志中的加载成功标志
loaded_marker() {
  case $1 in
    fastmotd) echo "fastmotd" ;;
    minimotd) echo "minimotd" ;;
    compat | fast) echo "mikumotd" ;;
    bare) echo "Done" ;;
  esac
}

start_proxy() {
  local name=$1 mode=$2
  local run_dir=$WORK/run-$name
  make_plugins "$WORK/plugins-$name" "$mode" || return 1
  mkdir -p "$run_dir/plugins"
  if [ "$mode" != "bare" ]; then
    cp -r "$WORK/plugins-$name/." "$run_dir/plugins/"
  fi

  local log=$WORK/$name-server.log
  : > "$log"
  (cd "$run_dir" && "$JCMD" -Xms512m -Xmx512m -jar "$WORK/velocity.jar" > "$log" 2>&1 & echo $! > "$WORK/$name.pid")

  for _ in $(seq 1 90); do
    if grep -q "Done" "$log" 2>/dev/null; then
      # 带插件场景再确认目标插件确实加载（而非因不兼容被跳过）
      if [ "$mode" != "bare" ]; then
        if ! grep -qi "Loaded plugin $(loaded_marker "$mode")" "$log"; then
          echo "插件未加载成功: $mode" >&2
          grep -iE "plugin|error" "$log" | tail -10 >&2 || true
          return 1
        fi
      fi
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
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$WORK/$name.pid"
  fi
  pkill -9 -f "velocity.jar" 2>/dev/null || true
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
  if ! lat=$("$JCMD" -cp "$WORK" MotdBench 127.0.0.1 "$PORT" --latency "$LATENCY_RUNS" | awk '{print $2}'); then
    echo "  延迟测量失败: $name（服务端日志尾部如下）" >&2
    tail -30 "$WORK/$name-server.log" >&2 || true
    return 1
  fi
  if ! read -r _ qps _ fail <<< "$("$JCMD" -cp "$WORK" MotdBench 127.0.0.1 "$PORT" --bench "$BENCH_SECONDS" --threads "$BENCH_THREADS")"; then
    echo "  QPS 测量失败: $name（服务端日志尾部如下）" >&2
    tail -30 "$WORK/$name-server.log" >&2 || true
    return 1
  fi
  echo "$name $round $lat $qps $fail" >> "$RESULTS"
  echo "  第 $round 轮：延迟 ${lat}µs，QPS $qps（失败 $fail）"
}

scenario_label() {
  case $1 in
    bare) echo "裸代理（无插件）" ;;
    minimotd) echo "MiniMOTD" ;;
    fastmotd) echo "FastMOTD" ;;
    compat) echo "MikuMOTD 事件模式" ;;
    fast) echo "MikuMOTD 快速路径" ;;
  esac
}

SCENARIOS=(bare)
[ -z "${SKIPPED[minimotd]:-}" ] && SCENARIOS+=(minimotd)
[ -z "${SKIPPED[fastmotd]:-}" ] && SCENARIOS+=(fastmotd)
SCENARIOS+=(compat fast)

echo "== 场景交替测量（共 $ROUNDS 轮）=="
declare -A SCENARIO_FAILED
for round in $(seq 1 "$ROUNDS"); do
  for scenario in "${SCENARIOS[@]}"; do
    if [ -n "${SCENARIO_FAILED[$scenario]:-}" ]; then
      continue
    fi
    if ! start_proxy "$scenario" "$scenario"; then
      SCENARIO_FAILED[$scenario]="运行失败"
      continue
    fi
    echo "[$scenario 第 $round 轮]"
    if ! measure "$scenario" "$round"; then
      SCENARIO_FAILED[$scenario]="测量失败"
      stop_proxy "$scenario"
      continue
    fi
    stop_proxy "$scenario"
  done
done

# 汇总（中位数）
median() {
  printf '%s\n' "$@" | sort -g | awk '{a[NR]=$1} END {if (NR % 2) print a[(NR+1)/2]; else print (a[NR/2]+a[NR/2+1])/2}'
}

{
  echo "## MOTD 插件性能对比（Velocity 4.2.0，runner 内自连，$ROUNDS 轮中位数）"
  echo
  echo "| 场景 | 平均延迟（µs/完整 ping） | QPS（${BENCH_THREADS} 线程 × ${BENCH_SECONDS}s） | 最大失败数 |"
  echo "|---|---|---|---|"
  for scenario in bare minimotd fastmotd compat fast; do
    if [ -n "${SKIPPED[$scenario]:-}" ]; then
      printf '| %s | 构建失败，跳过 | - | - |\n' "$(scenario_label "$scenario")"
      continue
    fi
    if [ -n "${SCENARIO_FAILED[$scenario]:-}" ]; then
      printf '| %s | 运行失败，跳过 | - | - |\n' "$(scenario_label "$scenario")"
      continue
    fi
    lats=$(awk -v s="$scenario" '$1==s {print $3}' "$RESULTS")
    qpss=$(awk -v s="$scenario" '$1==s {print $4}' "$RESULTS")
    fails=$(awk -v s="$scenario" '$1==s {print $5}' "$RESULTS" | sort -g | tail -1)
    printf '| %s | %s | %s | %s |\n' "$(scenario_label "$scenario")" "$(median $lats)" "$(median $qpss)" "${fails:-0}"
  done
  echo
  echo "> 共享 runner 上负载有漂移，本表仅供同批次内横向对比，绝对数值不代表生产环境。"
  echo "> MiniMOTD 与 FastMOTD 为现场源码构建并使用各自默认配置；MikuMOTD 场景使用对齐精简配置。"
} > "$SUMMARY"

cat "$SUMMARY"
