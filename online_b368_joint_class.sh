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
  python3 -IB "$shared/online_b368_compile_diagnostics.py" "$m" "$l" "$p/receiver/gradle/verification-metadata.xml" "$shared/online_b368_compile_guard.init.gradle" 2>>"$out" || echo B368_COMPILE_DIAGNOSTICS=REFUSED
}
stage=OFFLINE_COMPILE
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
    PYTHONDONTWRITEBYTECODE=1 GRADLE_USER_HOME="$p/consumer-home" GRADLE_RO_DEP_CACHE="$p/ro" \
    /usr/bin/python3 -IB "$shared/online_b368_compile_monitor.py" "$p" "$g" "$@" --offline --project-cache-dir "$p/compile-cache" \
    -I "$shared/online_b368_compile_guard.init.gradle" :app:checkStandardReleaseDuplicateClasses :app:compileStandardReleaseJavaWithJavac
' compile "$user_id" "$gid" "$p" "$j" "$g" "$shared" "${opts[@]}" >"$p/monitor.log" 2>&1 || rc=$?
grep -Ex 'B368_COMPILE_(ENTRY_MIB|MIN_MIB)=[0-9]{1,8}|B368_COMPILE_RESOURCE_OR_TIME_STOP=true' "$p/monitor.log" | awk '{sub(/^B368_COMPILE_/,"B368_JOINT_CLASS_");print}' || :
if [[ -f "$p/compile.log" ]]; then reason offline "$p/compile.log"; fi
printf 'B368_COMPILE_EXIT=%s\n' "$rc"
(( rc == 0 )) || exit "$rc"
stage=CLASS_READBACK
if ! grep -Fxq B368_PRIVATE_EXACT_GRAPH=31 "$p/compile.log"; then echo B368_CLASS_REFUSED=EXACT_GRAPH_MARKER; exit 1; fi
grep -Eq '^BUILD SUCCESSFUL( in .+)?$' "$p/compile.log" || { echo B368_CLASS_REFUSED=SUCCESS_MARKER; exit 1; }
for task in checkStandardReleaseDuplicateClasses compileStandardReleaseKotlin compileStandardReleaseJavaWithJavac; do
  if ! grep -Fxq "> Task :app:$task" "$p/compile.log"; then printf 'B368_CLASS_TASK_MARKER_REFUSED=%s\n' "$task"; exit 1; fi
done
readback_rc=0
env -i PATH=/usr/bin:/bin HOME="$p/home" GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GIT_OPTIONAL_LOCKS=0 /usr/bin/python3 -IB "$shared/read_compiled_release.py" \
  "$p/receiver" "$c" "$t" >"$p/classes.json" 2>"$p/classes.log" || readback_rc=$?
if (( readback_rc != 0 )); then
  grep -Ex 'B368_CLASS_REFUSED=(SOURCE_PATH|SOURCE_IDENTITY|SOURCE_DIRTY|BUILD_ROOT|UNEXPECTED_DEX_OR_APK|CLASS_ROOT_(KOTLIN|JAVAC)|CLASS_SYMLINK|CLASS_DUPLICATE|CLASS_MAGIC_OR_VERSION|CLASS_PACKAGE_(KOTLIN|JAVAC)|GENERATED_BUILD_CONFIG_(PATH|IDENTITY)|ASSERTION|READ_FAILED)' "$p/classes.log" || echo B368_CLASS_REFUSED=UNCLASSIFIED
  exit "$readback_rc"
fi
python3 -IB - "$p/classes.json" <<'PY'
import json,sys
from pathlib import Path
q=json.loads(Path(sys.argv[1]).read_bytes()); assert q['classMajor']==61 and not (q['dexBuilt'] or q['apkBuilt'] or q['formalAdmission'])
for k in ('kotlin','javac'):
    n=q['compilers'][k]['classes']; assert type(n) is int and n>0; print('B368_CLASS_READBACK='+k+':'+str(n))
PY
printf 'B368_COMPILE=PASS\n'
