# shellcheck disable=SC2034
SKIPUNZIP=1

# Ask root managers that implement the hot-install protocol to activate this
# update immediately. Managers that do not recognize it ignore the request.
export MODULE_HOT_INSTALL_REQUEST=true

DEBUG=@DEBUG@
SONAME=@SONAME@
SUPPORTED_ABIS="@SUPPORTED_ABIS@"

if [ "$BOOTMODE" ] && [ "$KSU" ]; then
  ui_print "- Installing from KernelSU app"
  ui_print "- KernelSU version: $KSU_KERNEL_VER_CODE (kernel) + $KSU_VER_CODE (ksud)"
elif [ "$BOOTMODE" ] && [ "$MAGISK_VER_CODE" ]; then
  ui_print "- Installing from Magisk app"
else
  ui_print "*********************************************************"
  ui_print "! Install from recovery is not supported"
  ui_print "! Please install from KernelSU or Magisk app"
  abort    "*********************************************************"
fi

VERSION=$(grep_prop version "${TMPDIR}/module.prop")
ui_print "- Installing $SONAME $VERSION"

# check architecture
support=false
for abi in $SUPPORTED_ABIS
do
  if [ "$ARCH" == "$abi" ]; then
    support=true
  fi
done
if [ "$support" == "false" ]; then
  abort "! Unsupported platform: $ARCH"
else
  ui_print "- Device platform: $ARCH"
fi

ui_print "- Extracting verify.sh"
unzip -o "$ZIPFILE" 'verify.sh' -d "$TMPDIR" >&2
if [ ! -f "$TMPDIR/verify.sh" ]; then
  ui_print "*********************************************************"
  ui_print "! Unable to extract verify.sh!"
  ui_print "! This zip may be corrupted, please try downloading again"
  abort    "*********************************************************"
fi
. "$TMPDIR/verify.sh"
extract "$ZIPFILE" 'customize.sh'  "$TMPDIR/.vunzip"
extract "$ZIPFILE" 'verify.sh'     "$TMPDIR/.vunzip"
extract "$ZIPFILE" 'sepolicy.rule' "$TMPDIR"

ui_print "- Extracting module files"
extract "$ZIPFILE" 'module.prop'     "$MODPATH"
extract "$ZIPFILE" 'post-fs-data.sh' "$MODPATH"
extract "$ZIPFILE" 'service.sh'      "$MODPATH"
extract "$ZIPFILE" 'config.sh'       "$MODPATH"
extract "$ZIPFILE" 'action.sh'       "$MODPATH"
extract "$ZIPFILE" 'uninstall.sh'    "$MODPATH"
extract "$ZIPFILE" 'webroot/index.html'       "$MODPATH"
extract "$ZIPFILE" 'webroot/css/app.css'      "$MODPATH"
extract "$ZIPFILE" 'webroot/js/bridge.js'     "$MODPATH"
extract "$ZIPFILE" 'webroot/js/app.js'        "$MODPATH"
extract "$ZIPFILE" 'webroot/js/kernelsu.js'   "$MODPATH"
mv "$TMPDIR/sepolicy.rule" "$MODPATH"

HAS32BIT=false
if [ -n "$(getprop ro.product.cpu.abilist32)" ] || [ -n "$(getprop ro.system.product.cpu.abilist32)" ]; then
  HAS32BIT=true
fi

mkdir "$MODPATH/zygisk"

if [ "$ARCH" = "arm64" ]; then
  if [ "$HAS32BIT" = true ]; then
    extract "$ZIPFILE" "lib/armeabi-v7a/lib$SONAME.so" "$MODPATH/zygisk" true
    mv "$MODPATH/zygisk/lib$SONAME.so" "$MODPATH/zygisk/armeabi-v7a.so"
  fi

  ui_print "- Extracting arm64 libraries"
  extract "$ZIPFILE" "lib/arm64-v8a/lib$SONAME.so" "$MODPATH/zygisk" true
  mv "$MODPATH/zygisk/lib$SONAME.so" "$MODPATH/zygisk/arm64-v8a.so"
elif [ "$ARCH" = "arm" ]; then
  ui_print "- Extracting arm libraries"
  extract "$ZIPFILE" "lib/armeabi-v7a/lib$SONAME.so" "$MODPATH/zygisk" true
  mv "$MODPATH/zygisk/lib$SONAME.so" "$MODPATH/zygisk/armeabi-v7a.so"
else
  abort "! Unsupported platform: $ARCH"
fi

# Extract each APK, then derive the DEX payload required by the
# InMemoryDexClassLoader bootstrap. Keeping DEX only inside the APK avoids
# storing the same bytes twice in the module ZIP.
extract_payload_dex() {
  payload_apk=$1
  installed_payload_dir=$2

  # A hot update may replace an APK with fewer DEX files. Remove all derived
  # files first so a stale classesN.dex cannot remain loadable.
  rm -f "$installed_payload_dir"/classes*.dex "$installed_payload_dir/dex.list"
  unzip -o "$payload_apk" 'classes*.dex' -d "$installed_payload_dir" >&2 ||
    abort "! Unable to extract DEX payload from $payload_apk"

  dex_max=0
  for dex_path in "$installed_payload_dir"/classes*.dex
  do
    [ -f "$dex_path" ] || continue
    dex_name=${dex_path##*/}
    case "$dex_name" in
      classes.dex)
        dex_number=1
        ;;
      classes[0-9]*.dex)
        dex_number=${dex_name#classes}
        dex_number=${dex_number%.dex}
        case "$dex_number" in
          ''|*[!0-9]*|0*|1) abort "! Invalid DEX payload entry: $dex_name" ;;
        esac
        ;;
      *)
        abort "! Invalid DEX payload entry: $dex_name"
        ;;
    esac
    if [ "$dex_number" -gt "$dex_max" ]; then
      dex_max=$dex_number
    fi
  done

  [ "$dex_max" -ge 1 ] || abort "! APK does not contain classes.dex: $payload_apk"
  : > "$installed_payload_dir/dex.list" ||
    abort "! Unable to create DEX list for $payload_apk"
  dex_number=1
  while [ "$dex_number" -le "$dex_max" ]
  do
    if [ "$dex_number" -eq 1 ]; then
      dex_name=classes.dex
    else
      dex_name="classes$dex_number.dex"
    fi
    [ -f "$installed_payload_dir/$dex_name" ] ||
      abort "! APK has a non-contiguous classes*.dex sequence: $payload_apk"
    printf '%s\n' "$dex_name" >> "$installed_payload_dir/dex.list" ||
      abort "! Unable to write DEX list for $payload_apk"
    dex_number=$((dex_number + 1))
  done
}

ui_print "- Extracting WeKite payload"
mkdir -p "$MODPATH/payload"
extract "$ZIPFILE" "payload/wekit.apk" "$MODPATH"
extract_payload_dex "$MODPATH/payload/wekit.apk" "$MODPATH/payload"
ui_print "  WeKite payload installed to $MODPATH/payload"

ui_print "- Setting permissions"
set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
set_perm_recursive "$MODPATH/payload" 0 0 0755 0644
set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/config.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print "- Installing WeChat Monet overlays"

# ── WeChat Monet overlays (Play 版微信 8.0.72, versionCode 3083/3084) ──────
# Static RRO overlays: Monet 基础主题 / 气泡 Pro / 经典气泡 / 多场景圆角 / 纯色底栏
# 气泡二选一: BubblePro 与 ClassicBubble 不能同时存在(都会覆盖气泡资源), 默认装 BubblePro
# APK 源保存在 $MODPATH/files/, 安装时复制选中项到 system/priv-app/, 以便 action.sh 运行时切换
#
# ⚠️ 关键兼容性: overlay APK 要求 minSdk 34 (Android 14+), 且为 isStatic=true 静态覆盖,
#    系统在开机阶段强制加载。若系统版本低于 14, 安装会导致开机卡在启动界面 (bootloop)!
#    因此必须检查 SDK 版本, 不满足则跳过 overlay (模块注入功能不受影响)。
MONET_TARGET_PACKAGE=com.tencent.mm
MONET_WECHAT_VERSION_CODE=$(dumpsys package com.tencent.mm 2>/dev/null | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1)
MONET_SDK_VERSION=$(getprop ro.build.version.sdk)

install_overlay_apk() {
  local name="$1"
  local target_dir="$MODPATH/system/priv-app/$name"
  mkdir -p "$target_dir"
  cp -f "$MODPATH/files/$name.apk" "$target_dir/$name.apk"
  chmod 0755 "$MODPATH/system" "$MODPATH/system/priv-app" "$target_dir" 2>/dev/null
  chmod 0644 "$target_dir/$name.apk" 2>/dev/null
}

if [ -z "$MONET_SDK_VERSION" ] || [ "$MONET_SDK_VERSION" -lt 34 ]; then
  ui_print "! 系统 Android 版本低于 14 (SDK ${MONET_SDK_VERSION:-未知}), 莫奈 overlay 要求 Android 14+"
  ui_print "! 已跳过 overlay 安装, 避免开机卡启动; 模块注入功能不受影响"
  rm -rf "$MODPATH/system/priv-app"
elif [ "$MONET_WECHAT_VERSION_CODE" = "3083" ] || [ "$MONET_WECHAT_VERSION_CODE" = "3084" ]; then
  ui_print "- WeChat Play 8.0.72 确认 (versionCode=$MONET_WECHAT_VERSION_CODE)"

  # 读取已有气泡选择 (默认 modern -> 气泡 Pro), 未安装过则默认 Pro
  MONET_BUBBLE=$(grep -E "^bubble_style=" "$MODPATH/config.conf" 2>/dev/null | cut -d'=' -f2- | tr -d '"' | tr -d "'")
  MONET_BUBBLE=${MONET_BUBBLE:-modern}

  ui_print "- 安装莫奈基础主题 + 圆角 + 底栏 overlay"
  install_overlay_apk "MonetWeChat"
  install_overlay_apk "MonetWeChatMultiSceneCorners"
  install_overlay_apk "MonetWeChatSolidTab"
  case "$MONET_BUBBLE" in
    classic)
      ui_print "- 气泡样式: 经典气泡"
      install_overlay_apk "MonetWeChatClassicBubble"
      ;;
    *)
      ui_print "- 气泡样式: 现代圆角"
      install_overlay_apk "MonetWeChatBubblePro"
      ;;
  esac
  # 保留 config.conf 记录, 供 action.sh 切换
  mkdir -p "$MODPATH"
  if ! grep -q "^bubble_style=" "$MODPATH/config.conf" 2>/dev/null; then
    echo "bubble_style=\"$MONET_BUBBLE\"" >> "$MODPATH/config.conf"
  fi
  set_perm_recursive "$MODPATH/system/priv-app" 0 0 0755 0644
  set_perm "$MODPATH/config.conf" 0 0 0644
  ui_print "- Monet overlays 安装完成 (重启后生效)"
else
  ui_print "! 当前微信 versionCode=${MONET_WECHAT_VERSION_CODE:-未知}, 莫奈 overlay 仅适配 Play 版 8.0.72"
  ui_print "! 模块其他功能不受影响, 莫奈覆盖将不生效"
  # 移除不匹配的 overlay, 避免系统加载错误资源
  rm -rf "$MODPATH/system/priv-app"
fi

# KernelSU assigns the WebUI directory's mode and SELinux context itself.
# Do not include $MODPATH/webroot in a recursive set_perm call.

OLD_MODULE_DIR=/data/adb/modules/wekit
OLD_TARGETS_FILE=/data/adb/wekit/injection-targets.tsv
NEW_STATE_DIR=/data/adb/wekite_zygisk
NEW_TARGETS_FILE=$NEW_STATE_DIR/injection-targets.tsv

if [ -f "$OLD_TARGETS_FILE" ] || [ -d "$OLD_MODULE_DIR" ]; then
  ui_print "*********************************************************"
  ui_print "- Migrating from old module ID"

  if [ -f "$OLD_TARGETS_FILE" ]; then
    if [ -e "$NEW_TARGETS_FILE" ]; then
      ui_print "- Keeping existing injection targets"
    else
      migration_file=$NEW_STATE_DIR/.injection-targets.migrate.$$
      umask 077
      mkdir -p "$NEW_STATE_DIR" ||
        abort "! Unable to create state directory: $NEW_STATE_DIR"
      chmod 700 "$NEW_STATE_DIR" ||
        abort "! Unable to set permissions on: $NEW_STATE_DIR"
      cp "$OLD_TARGETS_FILE" "$migration_file" || {
        rm -f "$migration_file"
        abort "! Unable to copy injection targets"
      }
      chmod 600 "$migration_file" || {
        rm -f "$migration_file"
        abort "! Unable to set permissions on migrated injection targets"
      }
      mv -f "$migration_file" "$NEW_TARGETS_FILE" || {
        rm -f "$migration_file"
        abort "! Unable to publish migrated injection targets"
      }
      ui_print "- Migrated injection targets"
    fi
  else
    ui_print "- No injection targets to migrate"
  fi

  if [ -d "$OLD_MODULE_DIR" ]; then
    touch "$OLD_MODULE_DIR/disable" ||
      abort "! Unable to disable old module"
    ui_print "- Old module disabled"
  fi
  ui_print "*********************************************************"
fi
