#!/system/bin/sh

MODDIR=${0%/*}
CONFIG_SCRIPT=$MODDIR/config.sh

# Module actions run from a root-manager environment that may have a sparse PATH.
export PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:/apex/com.android.runtime/bin:$PATH

# ── WeKite Monet 气泡切换 ──────────────────────────────────────────────────
# 音量+ = 切换气泡样式 (气泡Pro / 经典气泡)
# 音量- = 重启微信 (原有功能)
CONFIG_FILE="$MODDIR/config.conf"
OVERLAY_DIR="$MODDIR/system/priv-app"
FILES_DIR="$MODDIR/files"

listen_volume_key() {
  local key_events
  key_events=$(timeout 10 getevent -lc 4 2>/dev/null | grep 'KEY_VOLUME' | tail -n 2)
  case "$key_events" in
    *KEY_VOLUMEDOWN*) return 1 ;;
    *KEY_VOLUMEUP*) return 0 ;;
  esac
  # 无按键输入时默认走重启微信分支
  return 1
}

switch_bubble() {
  local current
  current=$(grep -E "^bubble_style=" "$CONFIG_FILE" 2>/dev/null | cut -d'=' -f2- | tr -d '"' | tr -d "'")
  current=${current:-modern}

  echo "=========================================="
  echo "  当前气泡样式: $([ "$current" = "classic" ] && echo '经典气泡' || echo '现代圆角')"
  echo "  音量+ = 切换气泡样式"
  echo "  音量- = 保持当前样式, 重启微信"
  echo "=========================================="

  if listen_volume_key; then
    # 先清掉两个气泡 overlay, 再装选中的那个
    rm -rf "$OVERLAY_DIR/MonetWeChatBubblePro" "$OVERLAY_DIR/MonetWeChatClassicBubble"
    if [ "$current" = "classic" ]; then
      new_style="pro"
      mkdir -p "$OVERLAY_DIR/MonetWeChatBubblePro"
      cp -f "$FILES_DIR/MonetWeChatBubblePro.apk" "$OVERLAY_DIR/MonetWeChatBubblePro/MonetWeChatBubblePro.apk"
    else
      new_style="classic"
      mkdir -p "$OVERLAY_DIR/MonetWeChatClassicBubble"
      cp -f "$FILES_DIR/MonetWeChatClassicBubble.apk" "$OVERLAY_DIR/MonetWeChatClassicBubble/MonetWeChatClassicBubble.apk"
    fi
    chmod 0755 "$OVERLAY_DIR" "$OVERLAY_DIR"/* 2>/dev/null
    chmod 0644 "$OVERLAY_DIR"/*/*.apk 2>/dev/null
    # 更新配置
    if grep -q "^bubble_style=" "$CONFIG_FILE" 2>/dev/null; then
      sed -i "s|^bubble_style=.*|bubble_style=\"$new_style\"|" "$CONFIG_FILE"
    else
      echo "bubble_style=\"$new_style\"" >> "$CONFIG_FILE"
    fi
    echo "- 已切换气泡样式: $([ "$new_style" = "classic" ] && echo '经典气泡' || echo '现代圆角')"
    echo "- 请重启微信或重启设备使覆盖生效"
    exit 0
  fi
}

# 有 files 源且存在 overlay 目录时才提供气泡切换
if [ -d "$OVERLAY_DIR" ] && [ -d "$FILES_DIR" ]; then
  switch_bubble
fi

if [ ! -x "$CONFIG_SCRIPT" ]; then
  echo "Unable to read WeKit Zygisk targets: $CONFIG_SCRIPT is unavailable" >&2
  exit 1
fi

target_rows=$("$CONFIG_SCRIPT" list)
list_status=$?
if [ "$list_status" -ne 0 ]; then
  echo "Unable to read WeKit Zygisk targets (exit $list_status)" >&2
  exit "$list_status"
fi

selected_target=$(printf '%s\n' "$target_rows" | awk -F '\t' '
  $3 == "1" {
    user_priority = ($1 == "0" ? 0 : 1)
    package_priority = ($2 == "com.tencent.mm" ? 0 : 1)
    if (!found ||
        user_priority < selected_user_priority ||
        (user_priority == selected_user_priority &&
         package_priority < selected_package_priority)) {
      selected_user_id = $1
      selected_package_name = $2
      selected_user_priority = user_priority
      selected_package_priority = package_priority
      found = 1
    }
  }
  END {
    if (found) {
      print selected_user_id "\t" selected_package_name
    }
  }
')

if [ -z "$selected_target" ]; then
  echo "No enabled WeKit Zygisk targets. Enable one in the module WebUI first." >&2
  exit 1
fi

tab=$(printf '\t')
IFS="$tab" read -r user_id package_name <<EOF
$selected_target
EOF

echo "- Restarting $package_name for Android user $user_id"
failed=false
if ! am force-stop --user "$user_id" "$package_name"; then
  echo "  Failed to force-stop $package_name for Android user $user_id" >&2
  failed=true
fi
if ! am start --user "$user_id" \
  -n "$package_name/com.tencent.mm.ui.LauncherUI"; then
  echo "  Failed to start $package_name for Android user $user_id" >&2
  failed=true
fi

if [ "$failed" = true ]; then
  echo "WeChat restart failed." >&2
  exit 1
fi

echo "WeChat restarted."
