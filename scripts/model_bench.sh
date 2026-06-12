#!/bin/zsh
# Model benchmark harness: rigorous success-rate + duration comparison.
# Protocol per run: reset device to HOME -> clear results -> run the canonical
# task (max-retry 0, single attempt) -> record success/steps/duration.
# Usage: model_bench.sh <label> <platform: ios|android> <runs> -- <arbigent ai args...>
# Appends one CSV line per run to /tmp/model-bench.csv:
#   label,platform,run,success,steps,duration_s,exit_code,timestamp
set -u
setopt null_glob 2>/dev/null
cd /Volumes/CSVolume/Documents/entertech/arbigent
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools:/opt/homebrew/bin"
BIN=./arbigent-cli/build/install/arbigent/bin/arbigent
UDID=00008101-001D29020E42001E
ANDROID_SN=FLHG6E989005P6
CSV=/tmp/model-bench.csv
TASK="先确认当前在手机主屏幕。然后打开应用商店，在商店内搜索 WeChat，打开它的应用详情页，找到用户评论区，并报告第一条用户评论的评论者名称与评论内容"

LABEL="$1"; PLATFORM="$2"; RUNS="$3"; shift 3
[ "$1" = "--" ] && shift

rehome_ios() {
  # kill any foreground user app -> SpringBoard; also clean orphan iproxy
  for r in 1 2 3 4 5 6; do
    P=$(xcrun devicectl device info processes --device $UDID 2>/dev/null | grep -iE "/AppStore.app/AppStore|/Health.app/Health|/Preferences.app/Preferences|/Music.app/Music|/MobileSafari.app/MobileSafari|/Chrome.app/Chrome|/Maps.app/Maps" | awk '{print $1}' | head -1)
    [ -z "$P" ] && break
    xcrun devicectl device process terminate --device $UDID --pid "$P" >/dev/null 2>&1
    sleep 1
  done
}
rehome_android() {
  # force-stop the store so each run starts from a fresh app state (otherwise the
  # store reopens on the previous run's review page = state leakage)
  adb -s $ANDROID_SN shell am force-stop com.android.vending >/dev/null 2>&1
  adb -s $ANDROID_SN shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  sleep 1
}

[ -f "$CSV" ] || echo "label,platform,run,success,steps,duration_s,exit_code,cache_hits,timestamp" > "$CSV"

for i in $(seq 1 "$RUNS"); do
  if [ "$PLATFORM" = "ios" ]; then rehome_ios; else rehome_android; fi
  # clear results AND the AI decision cache (it lives in arbigent-cache/ at the
  # project root, NOT under arbigent-result!): its key does not include the model
  # name, so a stale cache would replay one model's decisions for another
  rm -rf arbigent-result/jsonls/* arbigent-result/screenshots/* arbigent-result/result.yml arbigent-result/summary.txt arbigent-cache arbigent-cli/arbigent-cache 2>/dev/null
  LOG="/tmp/bench-${LABEL}-${PLATFORM}-${i}.log"
  if [ "$PLATFORM" = "android" ]; then export ANDROID_SERIAL=$ANDROID_SN; fi
  start=$(date +%s)
  "$BIN" run task "$TASK" --os "$PLATFORM" --max-step 22 --max-retry 0 "$@" > "$LOG" 2>&1
  ec=$?
  end=$(date +%s)
  steps=$(grep -E "^Steps:" arbigent-result/summary.txt 2>/dev/null | awk '{print $2}')
  hits=$(grep -E "^Decision cache:" arbigent-result/summary.txt 2>/dev/null | grep -oE "[0-9]+/[0-9]+" | head -1)
  dur=$(grep -E "^Duration:" arbigent-result/summary.txt 2>/dev/null | awk '{print $2}' | tr -d 's')
  succ=$([ $ec -eq 0 ] && echo 1 || echo 0)
  # fall back to wall clock when summary missing (crash)
  [ -z "$dur" ] && dur=$((end-start))
  echo "$LABEL,$PLATFORM,$i,$succ,${steps:-NA},${dur},$ec,${hits:-NA},$(date +%H:%M:%S)" >> "$CSV"
  echo "RUN $LABEL/$PLATFORM #$i -> success=$succ steps=${steps:-NA} dur=${dur}s exit=$ec cachehits=${hits:-NA}"
  cp arbigent-result/result.yml "/tmp/bench-${LABEL}-${PLATFORM}-${i}-result.yml" 2>/dev/null
done
