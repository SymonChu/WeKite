#!/system/bin/sh

MODDIR=${0%/*}
CONFIG_SCRIPT=$MODDIR/config.sh

# Module actions run from a root-manager environment that may have a sparse PATH.
export PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:/apex/com.android.runtime/bin:$PATH

# ── WeKite Monet 可选覆盖管理 ─────────────────────────────────────────────
# 音量+ = 切换气泡样式 (现代圆角 / 经典气泡)
# 音量- = 继续: 启用/停用其他覆盖 (圆角/底栏), 最后重启微信
CONFIG_FILE="$MODDIR/config.conf"
OVERLAY_DIR="$MODDIR/system/priv-app"
FILES_DIR="$MODDIR/files"

# 基础主题必须存在; 若缺失则先补装 (升级场景)
ensure_base_overlay() {
  if [ -d "$FILES_DIR" ] && [ ! -f "$OVERLAY_DIR/MonetWeChat/MonetWeChat.apk" ]; then
    mkdir -p "$OVERLAY_DIR/MonetWeChat"
    cp -f "$FILES_DIR/MonetWeChat.apk" "$OVERLAY_DIR/MonetWeChat/MonetWeChat.apk"
    chmod 0755 "$OVERLAY_DIR" "$OVERLAY_DIR/MonetWeChat" 2>/dev/null
    chmod 0644 "$OVERLAY_DIR/MonetWeChat/MonetWeChat.apk" 2>/dev/null
  fi
}

listen_volume_key() {
  local key_events
  key_events=$(timeout 10 getevent -lc 4 2>/dev/null | grep 'KEY_VOLUME' | tail -n 2)
  case "$key_events" in
    *KEY_VOLUMEDOWN*) return 1 ;;
    *KEY_VOLUMEUP*) return 0 ;;
  esac
  # 无按键输入时默认继续 (不走切换分支)
  return 1
}

get_conf() {
  local key="$1" default="$2" value
  value=$(grep -E "^${key}=" "$CONFIG_FILE" 2>/dev/null | head -n1 | cut -d'=' -f2- | tr -d '"' | tr -d "'")
  [ -n "$value" ] && echo "$value" || echo "$default"
}

set_conf() {
  local key="$1" value="$2"
  mkdir -p "$(dirname "$CONFIG_FILE")"
  touch "$CONFIG_FILE"
  if grep -q -E "^${key}=" "$CONFIG_FILE"; then
    sed -i "s|^${key}=.*|${key}=\"${value}\"|" "$CONFIG_FILE"
  else
    echo "${key}=\"${value}\"" >> "$CONFIG_FILE"
  fi
}

install_overlay() {
  local name="$1"
  local target_dir="$OVERLAY_DIR/$name"
  mkdir -p "$target_dir"
  cp -f "$FILES_DIR/$name.apk" "$target_dir/$name.apk"
  chmod 0755 "$OVERLAY_DIR" "$target_dir" 2>/dev/null
  chmod 0644 "$target_dir/$name.apk" 2>/dev/null
}

remove_overlay() {
  rm -rf "$OVERLAY_DIR/$1"
}

# 气泡切换: 音量+ = 切换, 音量- = 继续到下一个菜单
switch_bubble() {
  local current new_style
  current=$(get_conf "bubble_style" "modern")

  echo "=========================================="
  echo "  WeKite Monet 覆盖管理"
  echo "  当前气泡样式: $([ "$current" = "classic" ] && echo '经典气泡' || echo '现代圆角')"
  echo "  音量+ = 切换气泡样式"
  echo "  音量- = 继续 (管理圆角/底栏)"
  echo "=========================================="

  if listen_volume_key; then
    # 清掉两个气泡 overlay, 再装选中的那个
    remove_overlay "MonetWeChatBubblePro"
    remove_overlay "MonetWeChatClassicBubble"
    if [ "$current" = "classic" ]; then
      new_style="modern"
      install_overlay "MonetWeChatBubblePro"
    else
      new_style="classic"
      install_overlay "MonetWeChatClassicBubble"
    fi
    set_conf "bubble_style" "$new_style"
    echo "- 已切换气泡样式: $([ "$new_style" = "classic" ] && echo '经典气泡' || echo '现代圆角')"
    echo "- 请重启微信或重启设备使覆盖生效"
    exit 0
  fi
}

# 圆角/底栏开关: 音量+ = 切换当前项, 音量- = 下一项
manage_optional_overlays() {
  local corners_enabled tab_enabled
  corners_enabled=$(get_conf "multi_scene_corners_enabled" "0")
  tab_enabled=$(get_conf "solid_tab_enabled" "0")

  echo "=========================================="
  echo "  多场景圆角: $([ "$corners_enabled" = "1" ] && echo '已启用' || echo '已停用')"
  echo "  音量+ = 切换圆角  |  音量- = 下一项"
  echo "=========================================="
  if listen_volume_key; then
    if [ "$corners_enabled" = "1" ]; then
      remove_overlay "MonetWeChatMultiSceneCorners"
      set_conf "multi_scene_corners_enabled" "0"
      echo "- 已停用多场景圆角"
    else
      install_overlay "MonetWeChatMultiSceneCorners"
      set_conf "multi_scene_corners_enabled" "1"
      echo "- 已启用多场景圆角"
    fi
    exit 0
  fi

  echo "=========================================="
  echo "  纯色底栏: $([ "$tab_enabled" = "1" ] && echo '已启用' || echo '已停用')"
  echo "  音量+ = 切换底栏  |  音量- = 完成"
  echo "=========================================="
  if listen_volume_key; then
    if [ "$tab_enabled" = "1" ]; then
      remove_overlay "MonetWeChatSolidTab"
      set_conf "solid_tab_enabled" "0"
      echo "- 已停用纯色底栏"
    else
      install_overlay "MonetWeChatSolidTab"
      set_conf "solid_tab_enabled" "1"
      echo "- 已启用纯色底栏"
    fi
    exit 0
  fi

  echo "- 覆盖配置完成, 请重启微信或重启设备使覆盖生效"
  exit 0
}

# 有 files 源才提供覆盖管理
if [ -d "$FILES_DIR" ]; then
  ensure_base_overlay
  switch_bubble
  manage_optional_overlays
fi

# ── 原有功能: 重启微信 ─────────────────────────────────────────────────────
if [ ! -x "$CONFIG_SCRIPT" ]; then
  echo "Unable to read WeKite Zygisk targets: $CONFIG_SCRIPT is unavailable" >&2
  exit 1
fi

target_rows=$("$CONFIG_SCRIPT" list)
list_status=$?
if [ "$list_status" -ne 0 ]; then
  echo "Unable to read WeKite Zygisk targets (exit $list_status)" >&2
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
  echo "No enabled WeKite Zygisk targets. Enable one in the module WebUI first." >&2
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
