#!/system/bin/sh
# APK-owned launcher lifecycle. Fixed official features and safe third-party IDs are accepted.
#
# v4 keeps the foundation and imported launcher payloads separately:
#   runtime-core.js + official payloads + wrapped third-party payloads +
#   runtime-commit.js is staged and published as one hook.js. Fixed-name digest
# receipts protect the published combination and each imported payload. They
# identify this manager's deployment; they are not signatures.
BASE=/data/local/tmp/watchworkshop-runtime
SOURCE=/data/user/0/dev.codex.watchworkshop/files/monet
PKG=com.heytap.wearable.launcher
SHARED=/data/local/tmp/watchhide/hook.js
LEGACY=/data/local/tmp/watch-monet/hook.js
MARK=/data/user_de/0/com.heytap.wearable.launcher/files/watchworkshop-runtime.json
# Public generated foundation pins. Legacy theme is retained only to recognize
# released v2 tuples; new theme payloads use fixed-name digest receipts.
# The released v3 tuple is retained only to recognize and migrate deployed v3.
V3_CORE_SHA=3a403add0b743cd8402bd5011bca0275024daf9197662de1e15fdc1054bd6ee6
V3_COMMIT_SHA=c67d3f0ebb9fdcde61ea508b933a56d0c5da3b95695cd9fc19f70310bdfb211f
V3_SERVICE_SHA=8aed10eae6decece2abbd013a15c58ccf5b3ff5033c3c4bfa335d8e01648cbb7
CORE_SHA=3729267f6fdd08bb200510b04f84a7166c55bc5c876405befd68091b80a1113c
COMMIT_SHA=c67d3f0ebb9fdcde61ea508b933a56d0c5da3b95695cd9fc19f70310bdfb211f
OLD_THEME_SHA=706205ab96a67bfca062bca5152f89fe7cfaddc5ed9db2fe121d1697723dca74
OLD_HOOK_SHA=473ab51ed1888c278a414877eaafe04b59c6aa6d02a670548a50982ecac6aa35
OLD_SERVICE_SHA=0039a0fc5098695bdee8d0982b7a1f6dc8a150d944b08f5763c25c008de4ffff
# RUNTIME_SHA is the released v2 hook baseline, not the new composite pin.
RUNTIME_SHA=b712c05bfd1683e79371f5ad2a1891f0bad79b6c014adda9fa54ac9259fc249d
SERVICE_SHA=a474d0e4a2c395e694b7ea8fc3e2e3fdbd19bf2312aff504cc70a0df70e80841
CODE24_RUNTIME_SHA=b712c05bfd1683e79371f5ad2a1891f0bad79b6c014adda9fa54ac9259fc249d
CODE24_SERVICE_SHA=924a2cd72639dda60cef5accd4b67d6fd338168ab037476fea8e2182f725dfa4
FRIDA_SHA=e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea
FEATURES='monet widgets capture'
STAGE=
KEEP_STAGE=0
fail() { echo "$1"; exit 1; }
cleanup() {
  if [ -n "$STAGE" ] && [ "$KEEP_STAGE" = 0 ]; then rm -rf "$STAGE"; fi
}
trap cleanup 0
sha() { sha256sum "$1" 2>/dev/null | cut -d ' ' -f 1; }
has_feature() { case " $1 " in *" $2 "*) return 0;; esac; return 1; }
add_feature() { has_feature "$1" "$2" || printf '%s %s' "$1" "$2"; }
safe_id() {
  local id=$1 part old_ifs=$IFS
  [ ${#id} -le 80 ] || return 1
  case "$id" in ''|.*|*.|*..*|*[!a-z0-9._-]*) return 1;; esac
  IFS=.
  set -- $id
  IFS=$old_ifs
  [ $# -ge 2 ] || return 1
  for part do
    case "$part" in ''|[!a-z0-9]*|*[!a-z0-9]|*[!a-z0-9_-]*) return 1;; esac
  done
  return 0
}
third_id() {
  safe_id "$1" || return 1
  has_feature "$FEATURES" "$1" && return 1
  case "$1" in dev.owornate.monet|dev.owornate.widgets|dev.owornate.capture|monet.disabled|widgets.disabled|capture.disabled) return 1;; esac
  return 0
}
sorted_third() {
  local f
  for f in $1; do has_feature "$FEATURES" "$f" || printf '%s\n' "$f"; done | LC_ALL=C sort
}
third_modules() {
  local p name
  for p in "$BASE"/modules/*; do
    [ -d "$p" ] || continue
    name=${p##*/}
    third_id "$name" && [ -f "$p/installed" ] && printf '%s\n' "$name"
  done | LC_ALL=C sort
}
shared_ok() {
  case "$(sha "$SHARED")" in
    6e46e44d3b4f5fc2162b60dd90b5de2b3c2e4f4cfcf50d87eee5c5071883f722|0a5654f86c5fa2570d74f9877ff9abe974dcb82ba71c66869b4607f09182c07d|9b8116a64219e919e0f76777f9bbefb4f42821e651a337d109a21a3a457823f1|4bc93055211b1c070155b45808955b6e41380c51edc63010ba8f0f759c508ac0|d0d3406f1ce940f20f2089b2962c41d5eb0830249cd781887f37fecc90c088d7|aff84e9105a66738234398db5e5beef4fd67b384a804308479cfda472ef349a1|fdbf56b324eb29fd37f9d92644b051194503024ea34fcca4b1ddcdd9ecb3d46a|46b4ae970215c889e36524c3969b66e45bba45100afd973daae07947f531ea19) ;;
    *) return 1;;
  esac
  [ "$(sha /data/local/tmp/watchface-theme/theme.jar)" = "$OLD_THEME_SHA" ]
}
old_owned() {
  [ -f "$BASE/installed" ] && [ "$(sha "$BASE/hook.js")" = "$OLD_HOOK_SHA" ] &&
    [ "$(sha "$BASE/theme.jar")" = "$OLD_THEME_SHA" ] &&
    [ "$(sha "$BASE/monet-service.sh")" = "$OLD_SERVICE_SHA" ]
}
known_v2() {
  [ "$(cat "$BASE/runtime-version" 2>/dev/null)" = 2 ] &&
    [ "$(sha "$BASE/theme.jar")" = "$OLD_THEME_SHA" ] || return 1
  case "$(sha "$BASE/hook.js"):$(sha "$BASE/runtime-service.sh")" in
    "$RUNTIME_SHA:$CODE24_SERVICE_SHA"|"$CODE24_RUNTIME_SHA:$CODE24_SERVICE_SHA") return 0;;
    *) return 1;;
  esac
}
v3_payload() {
  [ -f "$BASE/payloads/$1/hook.js" ] && [ -f "$BASE/payloads/$1/hook.sha256" ]
}
v4_payload() {
  [ -f "$BASE/payloads/$1/hook.js" ] && [ -f "$BASE/payloads/$1/hook.sha256" ]
}
structured_present() {
  [ "$(cat "$BASE/runtime-version" 2>/dev/null)" = 3 ] ||
    [ "$(cat "$BASE/runtime-version" 2>/dev/null)" = 4 ] ||
    [ -e "$BASE/runtime-core.js" ] || [ -e "$BASE/runtime-commit.js" ] ||
    [ -e "$BASE/hook.sha256" ] || [ -e "$BASE/payloads" ]
}
v3_combo() {
  local f
  {
    cat "$1/runtime-core.js" || return 1
    printf '\n;\n' || return 1
    for f in $FEATURES; do
      if [ -f "$1/payloads/$f/hook.js" ]; then
        cat "$1/payloads/$f/hook.js" || return 1
        printf '\n;\n' || return 1
      fi
    done
    cat "$1/runtime-commit.js" || return 1
  } | sha256sum | cut -d ' ' -f 1
}
wrap_third() {
  printf "Java.perform(function(){globalThis.__watchWorkshop.register('%s',function(plugin){\n" "$1" || return 1
  cat "$2" || return 1
  printf '\n;\n});});\n' || return 1
}
emit_v4() {
  local root=$1 ids=$2 f
  cat "$root/runtime-core.js" || return 1
  printf '\n;\n' || return 1
  for f in $FEATURES; do
    has_feature "$ids" "$f" || continue
    cat "$root/payloads/$f/hook.js" || return 1
    printf '\n;\n' || return 1
  done
  for f in $(sorted_third "$ids"); do
    wrap_third "$f" "$root/payloads/$f/hook.js" || return 1
    printf '\n;\n' || return 1
  done
  cat "$root/runtime-commit.js" || return 1
}
v4_combo() {
  emit_v4 "$1" "$2" | sha256sum | cut -d ' ' -f 1
}
v3_owned() {
  local f d name actual receipt theme_ok=0
  [ "$(cat "$BASE/runtime-version" 2>/dev/null)" = 3 ] || return 1
  [ "$(sha "$BASE/runtime-core.js")" = "$V3_CORE_SHA" ] || return 1
  [ "$(sha "$BASE/runtime-commit.js")" = "$V3_COMMIT_SHA" ] || return 1
  [ "$(sha "$BASE/runtime-service.sh")" = "$V3_SERVICE_SHA" ] || return 1
  [ -s "$BASE/hook.js" ] && [ -s "$BASE/hook.sha256" ] || return 1
  actual=$(sha "$BASE/hook.js")
  receipt=$(cat "$BASE/hook.sha256" 2>/dev/null)
  [ -n "$actual" ] && [ "$actual" = "$receipt" ] || return 1
  [ "$(v3_combo "$BASE")" = "$actual" ] || return 1
  for f in $FEATURES; do
    if v3_payload "$f"; then
      actual=$(sha "$BASE/payloads/$f/hook.js")
      receipt=$(cat "$BASE/payloads/$f/hook.sha256" 2>/dev/null)
      [ -n "$actual" ] && [ "$actual" = "$receipt" ] || return 1
      [ -e "$BASE/modules/$f" ] || return 1
      [ "$f" != monet ] || theme_ok=1
    elif [ -e "$BASE/payloads/$f" ]; then
      return 1
    fi
    if ! v3_payload "$f" && [ -e "$BASE/modules/$f" ]; then return 1; fi
  done
  for d in "$BASE"/payloads/*; do
    [ -e "$d" ] || continue
    name=${d##*/}
    has_feature "$FEATURES" "$name" || return 1
  done
  for d in "$BASE"/modules/*; do
    [ -e "$d" ] || continue
    name=${d##*/}
    case "$name" in
      monet|widgets|capture) [ -f "$d" ] && [ ! -L "$d" ] || return 1;;
      monet.disabled|widgets.disabled|capture.disabled)
        [ -f "$d" ] && [ ! -L "$d" ] && [ -f "$BASE/modules/${name%.disabled}" ] || return 1;;
      *) return 1;;
    esac
  done
  if [ "$theme_ok" = 1 ]; then
    [ -s "$BASE/theme.jar" ] && [ -f "$BASE/payloads/monet/theme.sha256" ] || return 1
    actual=$(sha "$BASE/theme.jar")
    receipt=$(cat "$BASE/payloads/monet/theme.sha256" 2>/dev/null)
    [ -n "$actual" ] && [ "$actual" = "$receipt" ] || return 1
    [ "$(head -c 2 "$BASE/theme.jar" 2>/dev/null)" = PK ] || return 1
  elif [ -e "$BASE/theme.jar" ] || [ -e "$BASE/payloads/monet/theme.sha256" ]; then
    return 1
  fi
  return 0
}
v4_owned() {
  local f d x name base actual receipt ids= theme_ok=0
  [ "$(cat "$BASE/runtime-version" 2>/dev/null)" = 4 ] || return 1
  [ "$(sha "$BASE/runtime-core.js")" = "$CORE_SHA" ] || return 1
  [ "$(sha "$BASE/runtime-commit.js")" = "$COMMIT_SHA" ] || return 1
  [ "$(sha "$BASE/runtime-service.sh")" = "$SERVICE_SHA" ] || return 1
  [ -d "$BASE/modules" ] && [ -d "$BASE/payloads" ] || return 1
  [ -s "$BASE/hook.js" ] && [ -s "$BASE/hook.sha256" ] || return 1
  for d in "$BASE"/modules/* "$BASE"/modules/.[!.]* "$BASE"/modules/..?*; do
    [ -e "$d" ] || continue
    name=${d##*/}
    if has_feature "$FEATURES" "$name"; then
      [ -f "$d" ] && [ ! -L "$d" ] || return 1
      [ -f "$BASE/payloads/$name/hook.js" ] || return 1
      continue
    fi
    case "$name" in
      monet.disabled|widgets.disabled|capture.disabled)
        [ -f "$d" ] && [ ! -L "$d" ] && [ -f "$BASE/modules/${name%.disabled}" ] || return 1
        continue;;
    esac
    third_id "$name" || return 1
    [ -d "$d" ] && [ ! -L "$d" ] || return 1
    [ -f "$d/installed" ] && [ ! -L "$d/installed" ] || return 1
    [ ! -e "$d/disabled" ] || { [ -f "$d/disabled" ] && [ ! -L "$d/disabled" ]; } || return 1
    [ -f "$BASE/payloads/$name/hook.js" ] && [ -f "$BASE/payloads/$name/hook.sha256" ] || return 1
    for x in "$d"/* "$d"/.[!.]* "$d"/..?*; do
      [ -e "$x" ] || continue
      base=${x##*/}
      case "$base" in installed|disabled) ;; *) return 1;; esac
    done
  done
  for d in "$BASE"/payloads/*; do
    [ -e "$d" ] || continue
    [ -d "$d" ] && [ ! -L "$d" ] || return 1
    name=${d##*/}
    has_feature "$FEATURES" "$name" || third_id "$name" || return 1
    if has_feature "$FEATURES" "$name"; then
      [ -f "$BASE/modules/$name" ] && [ ! -L "$BASE/modules/$name" ] || return 1
    else
      [ -d "$BASE/modules/$name" ] && [ ! -L "$BASE/modules/$name" ] || return 1
      [ -f "$BASE/modules/$name/installed" ] && [ ! -L "$BASE/modules/$name/installed" ] || return 1
    fi
    [ -f "$d/hook.js" ] && [ ! -L "$d/hook.js" ] || return 1
    [ -f "$d/hook.sha256" ] && [ ! -L "$d/hook.sha256" ] || return 1
    actual=$(sha "$d/hook.js")
    receipt=$(cat "$d/hook.sha256" 2>/dev/null)
    [ -n "$actual" ] && [ "$actual" = "$receipt" ] || return 1
    for x in "$d"/* "$d"/.[!.]* "$d"/..?*; do
      [ -e "$x" ] || continue
      base=${x##*/}
      case "$base" in
        hook.js|hook.sha256) ;;
        theme.sha256) [ "$name" = monet ] || return 1;;
        *) return 1;;
      esac
    done
    ids=$(add_feature "$ids" "$name")
    if [ "$name" = monet ]; then
      theme_ok=1
      [ -f "$d/theme.sha256" ] && [ ! -L "$d/theme.sha256" ] || return 1
    elif [ -e "$d/theme.sha256" ]; then return 1; fi
  done
  actual=$(sha "$BASE/hook.js")
  receipt=$(cat "$BASE/hook.sha256" 2>/dev/null)
  [ -n "$actual" ] && [ "$actual" = "$receipt" ] || return 1
  [ "$(v4_combo "$BASE" "$ids")" = "$actual" ] || return 1
  if [ "$theme_ok" = 1 ]; then
    [ -s "$BASE/theme.jar" ] && [ ! -L "$BASE/theme.jar" ] || return 1
    actual=$(sha "$BASE/theme.jar")
    receipt=$(cat "$BASE/payloads/monet/theme.sha256" 2>/dev/null)
    [ -n "$actual" ] && [ "$actual" = "$receipt" ] || return 1
    [ "$(head -c 2 "$BASE/theme.jar" 2>/dev/null)" = PK ] || return 1
  elif [ -e "$BASE/theme.jar" ] || [ -e "$BASE/payloads/monet/theme.sha256" ]; then
    return 1
  fi
  return 0
}
base_ready() { { [ -f "$BASE/manager-ready" ] || old_owned; } && [ "$(sha "$BASE/frida-inject")" = "$FRIDA_SHA" ]; }
runtime_kind() {
  if [ -f "$SHARED" ]; then shared_ok && echo shared || echo conflict
  elif [ -f "$LEGACY" ]; then echo conflict
  elif structured_present; then
    case "$(cat "$BASE/runtime-version" 2>/dev/null)" in
      3) v3_owned && echo own || echo conflict;;
      4) v4_owned && echo own || echo conflict;;
      *) echo conflict;;
    esac
  elif known_v2 || old_owned; then echo own
  elif [ -f "$BASE/hook.js" ] || [ -f "$BASE/theme.jar" ] || [ -f "$BASE/runtime-version" ] ||
       [ -f "$BASE/installed" ] || [ -f "$BASE/monet-service.sh" ] || [ -f "$BASE/runtime-service.sh" ]; then echo conflict
  elif base_ready; then echo own
  else echo setup; fi
}
legacy_installed() { [ -f "$BASE/modules/$1" ] || { [ "$1" = monet ] && old_owned; }; }
installed() {
  case "$(cat "$BASE/runtime-version" 2>/dev/null)" in
    3) v3_payload "$1";;
    4) v4_payload "$1";;
    *) legacy_installed "$1";;
  esac
}
legacy_enabled() {
  if [ "$1" = monet ] && old_owned; then [ ! -f "$BASE/disabled" ]
  else [ -f "$BASE/modules/$1" ] && [ ! -f "$BASE/modules/$1.disabled" ]; fi
}
enabled() {
  case "$(cat "$BASE/runtime-version" 2>/dev/null)" in
    3) [ -f "$BASE/modules/$1" ] && [ ! -f "$BASE/modules/$1.disabled" ] &&
        [ -f "$BASE/payloads/$1/hook.js" ] && [ -f "$BASE/payloads/$1/hook.sha256" ];;
    4) if has_feature "$FEATURES" "$1"; then
         [ -f "$BASE/modules/$1" ] && [ ! -f "$BASE/modules/$1.disabled" ]
       else
         [ -f "$BASE/modules/$1/installed" ] && [ ! -f "$BASE/modules/$1/disabled" ]
       fi && [ -f "$BASE/payloads/$1/hook.js" ] && [ -f "$BASE/payloads/$1/hook.sha256" ];;
    *) legacy_enabled "$1";;
  esac
}
any_enabled() {
  local f
  for f in $FEATURES; do enabled "$f" && return 0; done
  for f in $(third_modules); do enabled "$f" && return 0; done
  return 1
}
live() {
  local p actual f name value
  p=$(pidof "$PKG" 2>/dev/null)
  case "$p" in ''|*[!0-9]*) return 1;; esac
  actual=$(tr '\000' ' ' <"/proc/$p/cmdline" 2>/dev/null)
  set -- $actual
  [ "$#" = 1 ] && [ "$1" = "$PKG" ] || return 1
  grep -q '"version":2' "$MARK" 2>/dev/null && grep -q '"pid":'"$p"'[,}]' "$MARK" 2>/dev/null && grep -q '"error":""' "$MARK" 2>/dev/null || return 1
  for f in $FEATURES; do
    value=false; enabled "$f" && value=true
    grep -q '"'"$f"'":'"$value" "$MARK" 2>/dev/null || return 1
  done
  grep -q '"extensions":{' "$MARK" 2>/dev/null || return 1
  for f in $(third_modules); do
    value=false; enabled "$f" && value=true
    grep -Fq '"'"$f"'":'"$value" "$MARK" 2>/dev/null || return 1
  done
}
status() {
  local kind
  kind=$(runtime_kind)
  case "$kind" in
    shared)
      if third_id "$FEATURE"; then echo shared
      else [ "$FEATURE" = monet ] && echo shared || fail SHARED_RUNTIME; fi;;
    conflict|setup) echo "$kind";;
    own)
      if ! installed "$FEATURE"; then echo available
      elif [ "$(cat "$BASE/runtime-version" 2>/dev/null)" = 3 ]; then echo update
      elif [ "$(cat "$BASE/runtime-version" 2>/dev/null)" != 4 ] && legacy_installed "$FEATURE"; then echo update
      elif ! enabled "$FEATURE"; then echo disabled
      elif [ "$FEATURE" = monet ]; then echo installed
      elif live; then echo ready
      else echo installed; fi;;
  esac
}
launcher_pid() {
  local actual
  TARGET=$(pidof "$PKG" 2>/dev/null)
  case "$TARGET" in ''|*[!0-9]*) fail LAUNCHER_UNAVAILABLE;; esac
  actual=$(tr '\000' ' ' <"/proc/$TARGET/cmdline")
  set -- $actual
  [ "$#" = 1 ] && [ "$1" = "$PKG" ] || fail LAUNCHER_IDENTITY
}
reload_launcher() {
  launcher_pid
  kill -9 "$TARGET" || fail LAUNCHER_RESTART_FAILED
  am start -a android.intent.action.MAIN -c android.intent.category.HOME -p "$PKG" >/dev/null 2>&1 || true
}
project_flags() {
  local f value mask='{' sep=
  for f in $FEATURES; do
    value=0; enabled "$f" && value=1
    settings put system "codex_${f}_enabled" "$value" || fail SETTING_FAILED
  done
  for f in $(third_modules); do
    value=false; enabled "$f" && value=true
    mask="${mask}${sep}\"${f}\":${value}"
    sep=,
  done
  mask="${mask}}"
  settings put system owornate_launcher_plugins "$mask" || fail SETTING_FAILED
}
stop_service() {
  if [ -f "$BASE/runtime-service.sh" ]; then sh "$BASE/runtime-service.sh" stop >/dev/null || fail STOP_FAILED
  elif old_owned; then sh "$BASE/monet-service.sh" stop >/dev/null || fail STOP_FAILED; fi
}
source_hook() { printf '%s/plugins/%s/hook.js' "$SOURCE" "$1"; }
source_theme() { printf '%s/plugins/monet/theme.jar' "$SOURCE"; }
validate_source_hook() {
  local path
  path=$(source_hook "$1")
  [ -f "$path" ] || fail "PLUGIN_REQUIRED:$1"
  [ -s "$path" ] || fail "PLUGIN_INVALID:$1"
}
validate_source_theme() {
  local path
  path=$(source_theme)
  [ -f "$path" ] || fail PLUGIN_REQUIRED:monet
  [ -s "$path" ] && [ "$(head -c 2 "$path" 2>/dev/null)" = PK ] || fail PLUGIN_INVALID:monet
}
validate_source_foundation() {
  [ -f "$SOURCE/runtime-core.js" ] && [ -s "$SOURCE/runtime-core.js" ] || fail PAYLOAD_INVALID
  [ -f "$SOURCE/runtime-commit.js" ] && [ -s "$SOURCE/runtime-commit.js" ] || fail PAYLOAD_INVALID
  [ -f "$SOURCE/runtime-service.sh" ] && [ -s "$SOURCE/runtime-service.sh" ] || fail PAYLOAD_INVALID
  [ "$(sha "$SOURCE/runtime-core.js")" = "$CORE_SHA" ] || fail PAYLOAD_INVALID
  [ "$(sha "$SOURCE/runtime-commit.js")" = "$COMMIT_SHA" ] || fail PAYLOAD_INVALID
  [ "$(sha "$SOURCE/runtime-service.sh")" = "$SERVICE_SHA" ] || fail PAYLOAD_INVALID
}
stage_foundation() {
  if v4_owned; then
    cp "$BASE/runtime-core.js" "$STAGE/runtime-core.js" || fail COPY_FAILED
    cp "$BASE/runtime-commit.js" "$STAGE/runtime-commit.js" || fail COPY_FAILED
    cp "$BASE/runtime-service.sh" "$STAGE/runtime-service.sh" || fail COPY_FAILED
  else
    validate_source_foundation
    cp "$SOURCE/runtime-core.js" "$STAGE/runtime-core.js" || fail COPY_FAILED
    cp "$SOURCE/runtime-commit.js" "$STAGE/runtime-commit.js" || fail COPY_FAILED
    cp "$SOURCE/runtime-service.sh" "$STAGE/runtime-service.sh" || fail COPY_FAILED
  fi
  chmod 644 "$STAGE/runtime-core.js" "$STAGE/runtime-commit.js" || fail WRITE_FAILED
  chmod 755 "$STAGE/runtime-service.sh" || fail WRITE_FAILED
}
stage_payload() {
  local f=$1 from_source=$2 hook theme dest digest
  dest="$STAGE/payloads/$f"
  mkdir -p "$dest" || fail WRITE_FAILED
  if [ "$from_source" = 1 ]; then
    hook=$(source_hook "$f")
    cp "$hook" "$dest/hook.js" || fail COPY_FAILED
    if [ "$f" = monet ]; then
      theme=$(source_theme)
      cp "$theme" "$STAGE/theme.jar" || fail COPY_FAILED
    fi
  else
    cp "$BASE/payloads/$f/hook.js" "$dest/hook.js" || fail COPY_FAILED
    if [ "$f" = monet ]; then cp "$BASE/theme.jar" "$STAGE/theme.jar" || fail COPY_FAILED; fi
  fi
  chmod 644 "$dest/hook.js" || fail WRITE_FAILED
  digest=$(sha "$dest/hook.js")
  [ -n "$digest" ] || fail WRITE_FAILED
  printf '%s\n' "$digest" > "$dest/hook.sha256" || fail WRITE_FAILED
  if [ "$f" = monet ]; then
    chmod 644 "$STAGE/theme.jar" || fail WRITE_FAILED
    digest=$(sha "$STAGE/theme.jar")
    [ -n "$digest" ] || fail WRITE_FAILED
    printf '%s\n' "$digest" > "$dest/theme.sha256" || fail WRITE_FAILED
  fi
  chmod 644 "$dest/hook.sha256" || fail WRITE_FAILED
  if [ -f "$dest/theme.sha256" ]; then chmod 644 "$dest/theme.sha256" || fail WRITE_FAILED; fi
}
stage_runtime() {
  local f from_source digest
  STAGE=$BASE/.runtime-stage.$$
  rm -rf "$STAGE"
  mkdir -p "$STAGE/payloads" || fail WRITE_FAILED
  stage_foundation
  for f in $FEATURES; do
    has_feature "$TARGETS" "$f" || continue
    from_source=0
    if [ "$MIGRATE" = 1 ] || [ "$f" = "$REPLACE_FEATURE" ]; then from_source=1; fi
    stage_payload "$f" "$from_source"
  done
  for f in $(sorted_third "$TARGETS"); do
    from_source=0
    if [ "$MIGRATE" = 1 ] || [ "$f" = "$REPLACE_FEATURE" ]; then from_source=1; fi
    stage_payload "$f" "$from_source"
  done
  emit_v4 "$STAGE" "$TARGETS" > "$STAGE/hook.js" || fail WRITE_FAILED
  chmod 644 "$STAGE/hook.js" || fail WRITE_FAILED
  digest=$(sha "$STAGE/hook.js")
  [ -n "$digest" ] || fail WRITE_FAILED
  printf '%s\n' "$digest" > "$STAGE/hook.sha256" || fail WRITE_FAILED
  printf '4\n' > "$STAGE/runtime-version" || fail WRITE_FAILED
  chmod 644 "$STAGE/hook.sha256" "$STAGE/runtime-version" || fail WRITE_FAILED
}
commit_runtime() {
  local f old_payloads=
  [ -d "$STAGE/payloads" ] || fail STAGE_INCOMPLETE
  stop_service
  if [ -d "$BASE/payloads" ]; then
    old_payloads=$BASE/.payloads-old.$$
    mv "$BASE/payloads" "$old_payloads" || fail SWITCH_FAILED
  fi
  if ! mv "$STAGE/payloads" "$BASE/payloads"; then KEEP_STAGE=1; fail SWITCH_FAILED; fi
  for f in runtime-core.js runtime-commit.js runtime-service.sh hook.sha256 hook.js runtime-version; do
    if ! mv -f "$STAGE/$f" "$BASE/$f"; then KEEP_STAGE=1; fail SWITCH_FAILED; fi
  done
  if [ -f "$STAGE/theme.jar" ]; then
    if ! mv -f "$STAGE/theme.jar" "$BASE/theme.jar"; then KEEP_STAGE=1; fail SWITCH_FAILED; fi
  else
    rm -f "$BASE/theme.jar"
  fi
  rm -f "$BASE/installed" "$BASE/disabled" "$BASE/monet-service.sh"
  [ -z "$old_payloads" ] || rm -rf "$old_payloads"
  rmdir "$STAGE" 2>/dev/null || true
  STAGE=
  KEEP_STAGE=0
}
clear_runtime() {
  stop_service
  rm -f "$BASE/hook.js" "$BASE/theme.jar" "$BASE/runtime-core.js" "$BASE/runtime-commit.js" \
    "$BASE/runtime-service.sh" "$BASE/runtime-version" "$BASE/hook.sha256" \
    "$BASE/installed" "$BASE/disabled" "$BASE/monet-service.sh"
  rm -rf "$BASE/payloads"
}
target_enabled() { has_feature "$TARGET_ENABLED" "$1"; }
apply_modules() {
  local f d
  rm -rf "$BASE/modules"
  mkdir -p "$BASE/modules" || fail WRITE_FAILED
  for f in $FEATURES; do
    has_feature "$TARGETS" "$f" || continue
    touch "$BASE/modules/$f" || fail WRITE_FAILED
    target_enabled "$f" || touch "$BASE/modules/$f.disabled" || fail WRITE_FAILED
  done
  for f in $(sorted_third "$TARGETS"); do
    d="$BASE/modules/$f"
    mkdir -p "$d" || fail WRITE_FAILED
    touch "$d/installed" || fail WRITE_FAILED
    target_enabled "$f" || touch "$d/disabled" || fail WRITE_FAILED
  done
}
start_service() {
  sh "$BASE/runtime-service.sh" ensure >/dev/null || fail START_FAILED
}
reconcile() {
  local n
  project_flags
  stop_service
  reload_launcher
  if any_enabled; then
    start_service
    n=0
    while [ "$n" -lt 20 ]; do
      live && return 0
      sleep 1; n=$((n+1))
    done
    fail START_UNCONFIRMED
  fi
}
[ "$(id -u)" = 0 ] || fail ROOT_REQUIRED
[ "$(getprop ro.product.model)" = OW20W1 ] && [ "$(getprop ro.build.display.id)" = OW20W1_11_A.92 ] && [ "$(getprop ro.build.version.sdk)" = 27 ] && [ "$(getprop ro.product.cpu.abi)" = armeabi-v7a ] || fail DEVICE_UNSUPPORTED
ACTION=${1:-status}; FEATURE=${2:-monet}
has_feature "$FEATURES" "$FEATURE" || third_id "$FEATURE" || fail INVALID_FEATURE
case "$ACTION" in status) status; exit 0;; kind) runtime_kind; exit 0;; install|enable|disable|uninstall|update|repair) ;; *) fail INVALID_ACTION;; esac
case "$(runtime_kind)" in shared) fail SHARED_RUNTIME;; conflict) fail UNKNOWN_FILES;; setup) fail SETUP_REQUIRED;; esac
base_ready || fail SETUP_REQUIRED

TARGETS=
MIGRATE=0
REPLACE_FEATURE=
if [ "$(cat "$BASE/runtime-version" 2>/dev/null)" = 4 ]; then
  for f in $FEATURES; do v4_payload "$f" && TARGETS=$(add_feature "$TARGETS" "$f"); done
  for f in $(third_modules); do TARGETS=$(add_feature "$TARGETS" "$f"); done
elif v3_owned; then
  for f in $FEATURES; do v3_payload "$f" && TARGETS=$(add_feature "$TARGETS" "$f"); done
elif known_v2 || old_owned; then
  MIGRATE=1
  if old_owned && [ ! -f "$BASE/modules/monet" ]; then TARGETS='monet'
  else for f in $FEATURES; do [ ! -f "$BASE/modules/$f" ] || TARGETS=$(add_feature "$TARGETS" "$f"); done; fi
fi

case "$ACTION" in
  install)
    if ! installed "$FEATURE"; then TARGETS=$(add_feature "$TARGETS" "$FEATURE"); REPLACE_FEATURE=$FEATURE; fi;;
  update|repair)
    installed "$FEATURE" || fail MODULE_NOT_INSTALLED
    REPLACE_FEATURE=$FEATURE;;
  enable|disable)
    installed "$FEATURE" || fail MODULE_NOT_INSTALLED;;
  uninstall)
    installed "$FEATURE" || fail MODULE_NOT_INSTALLED
    next=
    for f in $TARGETS; do [ "$f" = "$FEATURE" ] || next=$(add_feature "$next" "$f"); done
    TARGETS=$next;;
esac

# Capture enabled intent before replacing old payload/module records.
TARGET_ENABLED=
for f in $TARGETS; do
  has_feature "$TARGETS" "$f" || continue
  keep=0
  if [ "$ACTION" = enable ] && [ "$f" = "$FEATURE" ]; then keep=1
  elif [ "$ACTION" = disable ] && [ "$f" = "$FEATURE" ]; then keep=0
  elif enabled "$f"; then keep=1; fi
  [ "$keep" = 0 ] || TARGET_ENABLED=$(add_feature "$TARGET_ENABLED" "$f")
done

if [ "$ACTION" = install ] && ! v3_owned && ! v4_owned && ! known_v2 && ! old_owned; then
  launcher_pid
  ! grep -q frida-agent-32.so "/proc/$TARGET/maps" 2>/dev/null || fail FOREIGN_RUNTIME
fi

# Legacy migration imports every feature that was previously installed. v3
# enable/disable/uninstall operate only on the imported runtime payloads above.
if [ "$MIGRATE" = 1 ]; then
  for f in $FEATURES; do legacy_installed "$f" && validate_source_hook "$f"; done
  legacy_installed monet && validate_source_theme
fi

NEED_STAGE=0
if [ "$MIGRATE" = 1 ]; then NEED_STAGE=1; fi
if [ "$(cat "$BASE/runtime-version" 2>/dev/null)" = 3 ] && [ -n "$TARGETS" ]; then NEED_STAGE=1; fi
if [ "$ACTION" = install ] && [ -n "$REPLACE_FEATURE" ]; then NEED_STAGE=1; fi
if [ "$ACTION" = update ] || [ "$ACTION" = repair ]; then NEED_STAGE=1; fi
if [ "$ACTION" = uninstall ] && [ -n "$TARGETS" ]; then NEED_STAGE=1; fi

if [ "$NEED_STAGE" = 1 ] && [ -n "$TARGETS" ]; then
  if [ -n "$REPLACE_FEATURE" ]; then validate_source_hook "$REPLACE_FEATURE"; fi
  if [ "$REPLACE_FEATURE" = monet ] ||
     { [ "$MIGRATE" = 1 ] && has_feature "$TARGETS" monet; }; then validate_source_theme; fi
  stage_runtime
  commit_runtime
elif [ "$MIGRATE" = 1 ] || [ -z "$TARGETS" ]; then
  clear_runtime
fi

apply_modules
reconcile
case "$ACTION" in
  install) echo INSTALLED;;
  update|repair) echo UPDATED;;
  enable) echo ENABLED;;
  disable|uninstall) echo DISABLED_OR_REMOVED;;
esac
