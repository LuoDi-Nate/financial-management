#!/usr/bin/env bash
# =========================================================
# v1.34.1 · 部署防卡死的几道闸,不用真的上 prod 也能验(qa-run 里跑:v1341-DEPLOY-GUARD-TESTS)
#
#   1. build_precheck      可用内存不够就不编;FORCE 能放行;读不到 meminfo 不拦
#   2. run_build_guarded   正常命令照常结束、退出码原样传回;下载刷屏不转出(日志里留着);
#                          内存掉到阈值以下 → 看门狗停掉整组进程,返回 137
#   3. check_prebuilt_jar  jar 版本 == 仓库检出版本才放行;读不出版本 / 文件不存在都拒绝
#   4. rollback.sh --expect-current --check-only   线上不是本次版本 → 退出 3 拒绝;是 → 放行
#
# 用假的 meminfo(FINANCE_MEMINFO)和假的 jar(python3 zipfile 造),全程几秒、不碰任何真东西。
# =========================================================
set -uo pipefail
RD="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=deploy/_build-guard.sh
. "$RD/deploy/_build-guard.sh"

PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL  $1 :: $2"; }
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
meminfo() { printf 'MemTotal:        3662000 kB\nMemFree:          100000 kB\nMemAvailable:    %s kB\n' "$(( $1 * 1024 ))" > "$T/meminfo"; }
export FINANCE_MEMINFO="$T/meminfo"

echo "── 1 · 编译前检查"
meminfo 1500; build_precheck >/dev/null 2>&1 && ok "可用 1500MB ≥ 1000MB → 放行" || bad "1500MB 应放行" "被拦了"
meminfo 600;  build_precheck >/dev/null 2>&1 && bad "600MB 应拦下" "放行了" || ok "可用 600MB < 1000MB → 拦下"
meminfo 600;  out="$(build_precheck 2>&1)"; grep -q -- "--jar" <<<"$out" && grep -q "什么都没动\|没有换任何东西" <<<"$out" \
  && ok "拦下时说清楚:线上没动 + 可以用 --jar" || bad "拦下的说明不全" "$out"
meminfo 600;  FINANCE_BUILD_FORCE=1 build_precheck >/dev/null 2>&1 && ok "FORCE=1 → 放行" || bad "FORCE 应放行" "被拦了"
meminfo 600;  FINANCE_BUILD_MIN_AVAIL_MB=500 build_precheck >/dev/null 2>&1 && ok "阈值可调(500MB)" || bad "阈值可调" "没生效"
FINANCE_MEMINFO="$T/nope" build_precheck >/dev/null 2>&1 && ok "读不到 meminfo → 不拦" || bad "读不到 meminfo 不该拦" "拦了"

echo "── 2 · 带看门狗编译"
export FINANCE_GUARD_INTERVAL=0.2
# 看门狗每次采样读一次可用内存。这里接管 mem_avail_mb,按给定序列逐次返回(读完停在最后一个)——
# 「恰好一次低」「连续两次低」都是确定的,不靠 sleep 去对齐采样时刻(那样测不出「一次低就停」的错误实现)
mem_seq() { printf '%s\n' "$@" > "$T/memseq"; : > "$T/memidx"; }
mem_avail_mb() { local i; i=$(wc -l < "$T/memidx"); echo . >> "$T/memidx"; sed -n "$((i + 1))p" "$T/memseq" | grep . || tail -1 "$T/memseq"; }
mem_seq 2000
out="$(run_build_guarded "$T/b1.log" bash -c 'echo 编译开始; echo "[INFO] Downloading from aliyun: x.jar"; echo "[INFO] BUILD SUCCESS"; exit 0' 2>&1)"; rc=$?
[[ $rc -eq 0 ]] && ok "正常结束 → 退出码 0" || bad "正常结束退出码" "$rc"
grep -q "BUILD SUCCESS" <<<"$out" && ! grep -q "Downloading from" <<<"$out" && ok "进度照常转出,下载刷屏不转出" || bad "输出过滤" "$out"
grep -q "Downloading from" "$T/b1.log" && ok "下载行仍留在日志文件里" || bad "日志文件" "$(cat "$T/b1.log")"
run_build_guarded "$T/b2.log" bash -c 'exit 7' >/dev/null 2>&1; rc=$?
[[ $rc -eq 7 ]] && ok "失败的命令 → 退出码原样传回(7)" || bad "失败退出码" "$rc"

mem_seq 2000 2000 120
MARK="guard-test-$$-$RANDOM"
t0=$SECONDS
# 被停的命令再派生一个子进程:只停主进程的话,子进程会成孤儿继续跑 —— 必须整组停
out="$(run_build_guarded "$T/b3.log" bash -c "(exec -a $MARK sleep 30) & wait" 2>&1)"; rc=$?
grep -q "最低 120MB" <<<"$out" && ok "报出编译期间可用内存最低值" || bad "最低值" "$out"
took=$((SECONDS - t0))
[[ $rc -eq 137 ]] && ok "内存掉到 120MB → 看门狗停掉编译,返回 137(用时 ${took}s)" || bad "看门狗没动手" "rc=$rc · ${took}s · $out"
(( took < 10 )) && ok "几秒内就停,不会挂着" || bad "停得太慢" "${took}s"
sleep 0.5; pgrep -f "$MARK" >/dev/null && bad "被停的进程还活着" "$(pgrep -fa "$MARK")" || ok "整组进程都停干净了"
grep -q "看门狗" <<<"$out" && ok "停的时候说明了原因" || bad "看门狗没留话" "$out"

mem_seq 2000 250 2000 2000 250 2000
run_build_guarded "$T/b4.log" bash -c 'sleep 2; exit 0' >/dev/null 2>&1; rc=$?
[[ $rc -eq 0 ]] && ok "低一次马上回来(两回)→ 不误杀(要连续两次才停)" || bad "单次抖动误杀" "rc=$rc"
[[ $(wc -l < "$T/memidx") -ge 6 ]] && ok "看门狗确实在持续采样($(wc -l < "$T/memidx") 次)" || bad "采样次数太少,这条测不出东西" "$(wc -l < "$T/memidx")"

echo "── 3 · 预编译 jar 核对"
mkjar() { python3 - "$1" "$2" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w') as z:
    if sys.argv[2] != '-':
        z.writestr('BOOT-INF/classes/application.yml', 'app:\n  version: ${APP_VERSION:%s}\n' % sys.argv[2])
    z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
PY
}
mkjar "$T/ok.jar" 1.34.1; mkjar "$T/old.jar" 1.34.0; mkjar "$T/novers.jar" -
[[ "$(jar_app_version "$T/ok.jar")" == "1.34.1" ]] && ok "jar_app_version 读得出 1.34.1" || bad "jar_app_version" "$(jar_app_version "$T/ok.jar")"
check_prebuilt_jar "$T/ok.jar" 1.34.1 >/dev/null 2>&1 && ok "版本一致 → 放行" || bad "版本一致应放行" "被拒"
check_prebuilt_jar "$T/old.jar" 1.34.1 >/dev/null 2>&1 && bad "版本不一致应拒绝" "放行了" || ok "jar v1.34.0 ≠ 检出 v1.34.1 → 拒绝"
check_prebuilt_jar "$T/novers.jar" 1.34.1 >/dev/null 2>&1 && bad "读不出版本应拒绝" "放行了" || ok "读不出版本 → 拒绝"
check_prebuilt_jar "$T/missing.jar" 1.34.1 >/dev/null 2>&1 && bad "文件不存在应拒绝" "放行了" || ok "文件不存在 → 拒绝"
[[ -n "$(repo_app_version "$RD")" ]] && ok "repo_app_version 读得出仓库版本($(repo_app_version "$RD"))" || bad "repo_app_version" "空"

echo "── 4 · 回滚前核对线上版本(rollback.sh --check-only)"
mkdir -p "$T/app"
cp "$T/old.jar" "$T/app/app.jar"; mkjar "$T/app/app.jar.prev" 1.33.0
out="$(FINANCE_APP_DIR="$T/app" bash "$RD/deploy/rollback.sh" --expect-current v1.34.1 --check-only 2>&1)"; rc=$?
[[ $rc -eq 3 ]] && grep -q "没换上去" <<<"$out" && ok "线上 v1.34.0 ≠ 本次 v1.34.1 → 拒绝回滚(退出 3)" || bad "新 jar 没换上去时应拒绝" "rc=$rc · $out"
cp "$T/ok.jar" "$T/app/app.jar"
out="$(FINANCE_APP_DIR="$T/app" bash "$RD/deploy/rollback.sh" --expect-current 1.34.1 --check-only 2>&1)"; rc=$?
[[ $rc -eq 0 ]] && grep -q "可以回滚" <<<"$out" && ok "线上就是 v1.34.1 → 放行(--check-only 不动东西)" || bad "应放行" "rc=$rc · $out"

echo
echo "部署闸测试:PASS=$PASS  FAIL=$FAIL"
[[ $FAIL -eq 0 ]]
