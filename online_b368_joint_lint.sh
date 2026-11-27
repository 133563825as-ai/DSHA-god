set -euo pipefail
umask 077
p=$1; shared=$2; j=$3; g=$4; user_id=$5; gid=$6; c=$7; t=$8; shift 8
opts=("$@")
out="$p/lane.private.log"
cd "$p/receiver"
reason() {
  local m=$1 l=$2
  if grep -Fq 'Dependency verification failed' "$l"; then printf 'B368_COMPILE_REASON=%s:DEPENDENCY_VERIFICATION\n' "$m"; fi
  if grep -Fq 'available for offline mode' "$l"; then printf 'B368_COMPILE_REASON=%s:OFFLINE_CACHE_MISS\n' "$m"; fi
  if grep -Eqi 'No matching variant|no compatible variant' "$l"; then printf 'B368_COMPILE_REASON=%s:VARIANT\n' "$m"; fi
  if grep -Fq 'B368_ACTUAL_ORIGIN_REFUSED:' "$l"; then printf 'B368_COMPILE_REASON=%s:GUARD\n' "$m"; fi
  if [[ $m = offline ]]; then
    for code in AGP_REPORT_TASKS AGP_REPORT_PATH AGP_REPORT_RESULT_BINDING AGP_REPORT_CONTRACT_EXISTS; do
      if grep -Fq "B368_LINT_EXEC_REFUSED:$code" "$l"; then printf 'B368_LINT_DECLARATION_REFUSED=%s\n' "$code"; fi
    done
  fi
  if grep -Eq 'Could not resolve|Could not find|Could not get resource' "$l"; then printf 'B368_COMPILE_REASON=%s:RESOLVER\n' "$m"; fi
  if grep -Eq 'NDK not configured|No version of NDK matched|Failed to find target' "$l"; then printf 'B368_COMPILE_REASON=%s:SDK_NDK\n' "$m"; fi
  if grep -Fq 'B368_PRIVATE_EXACT_GRAPH=31' "$l"; then echo B368_COMPILE_GRAPH=31; fi
  python3 -IB - "$m" "$l" <<'PY'
import re,sys
from pathlib import Path
phase,path=sys.argv[1:]; t=Path(path).read_text(errors='replace')
result={'GOOGLE':r"Could not (?:GET|HEAD|get resource)[^\n]+https://dl\.google\.com/",
        'CENTRAL':r"Could not (?:GET|HEAD|get resource)[^\n]+https://repo\.maven\.apache\.org/",
        'HTTP_403':r'(?:status code|HTTP)[ :]+403\b','HTTP_429':r'(?:status code|HTTP)[ :]+429\b',
        'HTTP_5XX':r'(?:status code|HTTP)[ :]+5\d\d\b','TLS':r'(?i)PKIX|handshake|peer not authenticated',
        'TIMEOUT':r'(?i)timed out|timeout'}
for k,v in result.items():
    if re.search(v,t):print(f'B368_COMPILE_REASON={phase}:{k}')
PY
  if [[ $m = lint || $m = offline ]]; then
    python3 -IB "$shared/online_b368_lint_diagnostics.py" "$m" "$l" "$p/receiver/gradle/verification-metadata.xml" "$shared/online_b368_lint_guard.init.gradle" 2>>"$out" || echo B368_LINT_DIAGNOSTICS=REFUSED
  else
    python3 -IB "$shared/online_b368_compile_diagnostics.py" "$m" "$l" "$p/receiver/gradle/verification-metadata.xml" "$shared/online_b368_compile_guard.init.gradle" 2>>"$out" || echo B368_COMPILE_DIAGNOSTICS=REFUSED
  fi
}
stage=OFFLINE_LINT
rc=0
sudo -n /usr/bin/unshare -nm --propagation private /bin/bash -ec '
  uid=$1; gid=$2; p=$3; j=$4; g=$5; shared=$6; shift 6
  /usr/sbin/ip link set lo up
  test "$(/usr/sbin/ip -o link | wc -l)" = 1
  test -z "$(/usr/sbin/ip route show)"
  test -z "$(/usr/sbin/ip -6 route show default)"
  mount --bind "$shared/producer-home/caches" "$p/ro"
  mount -o remount,bind,ro,nosuid,nodev,noexec "$p/ro"
  test -d "$p/ro/modules-2"
  mount --bind "$shared/sdk" "$shared/sdk"
  mount -o remount,bind,ro,nosuid,nodev "$shared/sdk"
  for d in app/src tools build-logic gradle ci .git .github rust; do
    x="$p/receiver/$d"
    test "$(realpath -e "$x")" = "$x"
    mount --bind "$x" "$x"
    mount -o remount,bind,ro,nosuid,nodev "$x"
  done
  test -z "$(ls -A "$p/consumer-home")"
  exec /usr/bin/setpriv --reuid="$uid" --regid="$gid" --clear-groups --no-new-privs /usr/bin/env -i \
    PATH="$j/bin:/usr/bin:/bin" JAVA_HOME="$j" ANDROID_HOME="$shared/sdk" HOME="$p/home" TMPDIR="$p/tmp" LC_ALL=C.UTF-8 \
    DSHA_PYTHON=/usr/bin/python3 GIT_OPTIONAL_LOCKS=0 GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_NO_LAZY_FETCH=1 \
    PYTHONDONTWRITEBYTECODE=1 GRADLE_USER_HOME="$p/consumer-home" GRADLE_RO_DEP_CACHE="$p/ro" B368_LINT_DECLARED_OUTPUTS="$p/lint.outputs.json" \
    /usr/bin/python3 -IB "$shared/online_b368_compile_monitor.py" "$p" "$g" "$@" --offline --project-cache-dir "$p/compile-cache" \
    -I "$shared/online_b368_lint_guard.init.gradle" :app:lintStandardRelease
' compile "$user_id" "$gid" "$p" "$j" "$g" "$shared" "${opts[@]}" >"$p/monitor.log" 2>&1 || rc=$?
grep -Ex 'B368_COMPILE_(ENTRY_MIB|MIN_MIB)=[0-9]{1,8}|B368_COMPILE_RESOURCE_OR_TIME_STOP=true' "$p/monitor.log" | awk '{sub(/^B368_COMPILE_/,"B368_JOINT_LINT_");print}' || :
if [[ -f "$p/compile.log" ]]; then reason offline "$p/compile.log"; fi
printf 'B368_LINT_GRADLE_EXIT=%s\n' "$rc"
if ! grep -Fxq 'B368_PRIVATE_LINT_EXACT_GRAPH=37' "$p/compile.log"; then
  echo B368_LINT_EXACT_GRAPH=REFUSED
  if (( rc != 0 )); then exit "$rc"; else exit 1; fi
fi
echo B368_LINT_EXACT_GRAPH=37
if [[ ! -s "$p/lint.outputs.json" ]]; then
  echo B368_LINT_REPORT_REFUSED=DECLARATION
  exit 1
fi
for task in lintAnalyzeStandardRelease lintReportStandardRelease; do
  if grep -Fxq "> Task :app:$task" "$p/compile.log" || grep -Fxq "> Task :app:$task FAILED" "$p/compile.log"; then
    printf 'B368_LINT_EXECUTED_TASK=%s\n' "$task"
  else
    printf 'B368_LINT_MISSING_TASK=%s\n' "$task"
    if (( rc != 0 )); then exit "$rc"; else exit 1; fi
  fi
done
stage=LINT_REPORT
if [[ -n $(find app/build -type f \( -name '*.apk' -o -name '*.dex' \) -print -quit) ]]; then echo B368_LINT_REPORT_REFUSED=UNEXPECTED_DEX_OR_APK; exit 1; fi
report_rc=0
python3 -IB "$shared/online_b368_lint_report.py" "$p/receiver/app/build" "$p/lint.outputs.json" >"$p/lint.summary" 2>>"$out" || report_rc=$?
grep -Ex 'B368_LINT_(REPORT_COUNT=(errors|warnings|other):[0-9]{1,6}|REPORT_SHA256=[a-f0-9]{64}|TOOL_STATUS=[0-7]|TEXT_COUNTERS=[0-9]{1,6}:[0-9]{1,6}|TEXT=UNAVAILABLE|REPORT_REFUSED=(DECLARATION|TOOL_STATUS|XML_DISABLED|XML_MISSING_OR_UNSAFE|XML_SCHEMA))' "$p/lint.summary" || echo B368_LINT_REPORT_REFUSED=NO_VERIFIED_REPORT
(( rc == 0 )) || exit "$rc"
(( report_rc == 0 )) || exit "$report_rc"
printf 'B368_LINT=PASS\n'
