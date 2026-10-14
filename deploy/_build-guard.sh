#!/usr/bin/env bash
# =========================================================
# deploy/_build-guard.sh · v1.34.1 · 在本机编译时不把机器拖死(deploy.sh source 本文件;下划线 = 内部文件)
#
# 由来(2026-10-14 · v1.34.0 首次上 prod):deploy.sh 在 prod 本机 mvn package,和线上服务 / MySQL /
# 同机别的常驻进程一起把 3.5G 内存顶满;那台机器 vm.swappiness=0,内核不换出空闲内存、改为反复丢弃
# 程序代码页再从盘读回 —— 磁盘读打满、CPU 却不高,SSH 握手超时、网页超时,不触发 OOM、永远不会自己好,
# 卡了 100 分钟靠控制台强制重启。编译本身其实只要 ~400MB(限堆后实测)。
#
# 三层:
#   1. build_precheck     编译前看可用内存,不够就停下说清楚(要硬编:FINANCE_BUILD_FORCE=1)
#   2. MAVEN_OPTS 默认限堆 -Xmx768m(调用方没设才给)
#   3. run_build_guarded  编译期间看门狗:可用内存连续两次低于阈值就停掉编译。此时还没换 jar,线上继续跑旧版
# 另:check_prebuilt_jar —— 在别处编好的 jar(deploy.sh --jar)先核对版本号,对不上不换
#
# 可调(环境变量):
#   FINANCE_BUILD_MIN_AVAIL_MB   编译前至少要有的可用内存(默认 1000)
#   FINANCE_BUILD_KILL_AVAIL_MB  编译中低于它就停(默认 300)
#   FINANCE_BUILD_FORCE=1        跳过编译前检查(看门狗仍在)
#   FINANCE_MEMINFO              读哪个 meminfo(默认 /proc/meminfo;测试用)
#   FINANCE_GUARD_INTERVAL       看门狗采样间隔秒(默认 3;测试用)
# 测试:scripts/test-deploy-guards.sh
# =========================================================

# MemAvailable(MB)。读不到返回空 —— 调用方当作「不知道」处理,不拦
mem_avail_mb() {
  awk '/^MemAvailable:/{print int($2/1024)}' "${FINANCE_MEMINFO:-/proc/meminfo}" 2>/dev/null
}

# 编译前检查。返回 0 = 可以编;1 = 不该编(说明已打到 stderr)
build_precheck() {
  local need="${FINANCE_BUILD_MIN_AVAIL_MB:-1000}" avail
  avail="$(mem_avail_mb)"
  if [[ -z "$avail" ]]; then
    echo "  读不到可用内存(${FINANCE_MEMINFO:-/proc/meminfo}),跳过编译前检查" >&2
    return 0
  fi
  if (( avail >= need )); then
    echo "  可用内存 ${avail}MB ≥ ${need}MB,可以在本机编译"
    return 0
  fi
  if [[ "${FINANCE_BUILD_FORCE:-0}" == "1" ]]; then
    echo "  可用内存 ${avail}MB < ${need}MB,但设了 FINANCE_BUILD_FORCE=1 —— 照样编(看门狗仍在)" >&2
    return 0
  fi
  cat >&2 <<EOF
  可用内存只有 ${avail}MB(< ${need}MB)。在本机编译可能把整台机器拖到卡死(磁盘读打满、SSH 都连不上)。
  没有换任何东西,线上还是原来的版本。可以:
    · 先停掉同机占内存的服务(比如别的常驻进程)再跑一次;
    · 或在别的机器编好 jar(mvn -DskipTests package),传上来用  sudo bash deploy/deploy.sh --jar <app.jar>;
    · 确认要硬编:FINANCE_BUILD_FORCE=1 sudo -E bash deploy/deploy.sh
EOF
  return 1
}

# 带看门狗跑编译:run_build_guarded <日志文件> <命令...>
#   命令在独立进程组里跑(setsid),输出写日志并实时转出(滤掉依赖下载刷屏)。
#   返回命令自己的退出码;被看门狗停掉时返回 137,并打印原因。
run_build_guarded() {
  local log="$1"; shift
  local kill_at="${FINANCE_BUILD_KILL_AVAIL_MB:-300}" every="${FINANCE_GUARD_INTERVAL:-3}"
  : > "$log"
  setsid "$@" >> "$log" 2>&1 < /dev/null &
  local pid=$!
  tail -n +1 -f --pid="$pid" "$log" 2>/dev/null \
    | grep --line-buffered -vE '^\[INFO\] (Downloading|Downloaded from|Progress )' &
  local tail_pid=$! low=0 killed=0 avail min=""
  while kill -0 "$pid" 2>/dev/null; do
    avail="$(mem_avail_mb)"
    if [[ -n "$avail" ]]; then
      [[ -z "$min" || "$avail" -lt "$min" ]] && min="$avail"
      if (( avail < kill_at )); then low=$((low + 1)); else low=0; fi
      if (( low >= 2 )); then
        echo "  ✗ 看门狗:编译中可用内存 ${avail}MB < ${kill_at}MB(连续两次)—— 停掉编译,免得把机器拖死" >&2
        kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
        sleep 2
        kill -KILL -- "-$pid" 2>/dev/null || true
        killed=1
        break
      fi
    fi
    sleep "$every"
  done
  local rc=0
  wait "$pid" 2>/dev/null || rc=$?
  wait "$tail_pid" 2>/dev/null || true
  [[ -n "$min" ]] && echo "  编译期间可用内存最低 ${min}MB"
  if (( killed )); then return 137; fi
  return "$rc"
}

# jar 里的 app.version 默认值(application.yml 的 ${APP_VERSION:x.y.z})
jar_app_version() {
  { unzip -p "$1" BOOT-INF/classes/application.yml 2>/dev/null \
      || python3 -c 'import sys,zipfile;sys.stdout.write(zipfile.ZipFile(sys.argv[1]).read("BOOT-INF/classes/application.yml").decode())' "$1" 2>/dev/null; } \
    | grep -oE 'APP_VERSION:[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1 | cut -d: -f2
}

# 仓库当前检出的 app.version 默认值
repo_app_version() {
  grep -oE 'APP_VERSION:[0-9]+\.[0-9]+(\.[0-9]+)?' "$1/src/main/resources/application.yml" 2>/dev/null | head -1 | cut -d: -f2
}

# 核对别处编好的 jar:check_prebuilt_jar <jar> <期望版本>。返回 0 = 可用
check_prebuilt_jar() {
  local jar="$1" want="$2" got
  [[ -f "$jar" ]] || { echo "  预编译 jar 不存在:$jar" >&2; return 1; }
  got="$(jar_app_version "$jar")"
  [[ -n "$got" ]] || { echo "  $jar 读不出版本号(不是本项目的 jar,或文件不完整)" >&2; return 1; }
  [[ -n "$want" ]] || { echo "  读不出仓库检出的版本号,没法核对 jar" >&2; return 1; }
  if [[ "$got" != "$want" ]]; then
    echo "  jar 是 v$got,仓库检出的是 v$want —— 对不上,不换" >&2
    return 1
  fi
  echo "  预编译 jar 版本 v$got 与仓库检出一致"
  return 0
}
