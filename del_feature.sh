#!/bin/bash
# Delete unwanted feature files - keep only the 46 needed features
cd /vol1/@appdata/trim.hermes/workspace/WeKit

SRC="app/src/main/java/io/github/we/lite/features/items"

# Directories to DELETE ENTIRELY
rm -rf "$SRC/beautify"
rm -rf "$SRC/batch"
rm -rf "$SRC/chat_input_bar_menu"
rm -rf "$SRC/debug"
rm -rf "$SRC/easter_egg"
rm -rf "$SRC/entertain"
rm -rf "$SRC/home_screen_menu"
rm -rf "$SRC/notifications"
rm -rf "$SRC/official_accounts"
rm -rf "$SRC/profile"
rm -rf "$SRC/scripting_java"
rm -rf "$SRC/scripting_js"
rm -rf "$SRC/shortvideos"
rm -rf "$SRC/system/servers"
rm -rf "$SRC/system/agent"
rm -rf "$SRC/voip"
rm -rf "$SRC/contacts/hidecontacts"

echo "Deleted all unwanted directories"

# For chat/ directory - remove specific files (keep only 7)
cd "$SRC/chat"
KEEP_CHAT=(
  AntiMessageRecall.kt
  DisplayGroupMemberRoles.kt
  MonitorGroupMemberOperations.kt
  AutoSpeechToText.kt
  DisableTypingStatusUploading.kt
  AutoEnableNoCompressOnSendMedia.kt
  AutoViewOriginalMedia.kt
  DisplayGroupMemberInviter.kt
)
for f in *.kt; do
  keep=false
  for k in "${KEEP_CHAT[@]}"; do
    if [ "$f" = "$k" ]; then keep=true; break; fi
  done
  if [ "$keep" = false ]; then rm -f "$f"; fi
done
# Remove chat subdirectories
rm -rf panel
echo "Chat: kept ${#KEEP_CHAT[@]} files"

# For moments/ directory - keep only 5 files
cd "$SRC/moments"
KEEP_MOMENTS=(
  AntiMomentsDelete.kt
  AntiMomentCommentsDelete.kt
  AutoLikeMoments.kt
  AutoMomentsBase.kt
  MomentsAutomationSettings.kt
  RemoveMomentsAds.kt
)
for f in *.kt; do
  keep=false
  for k in "${KEEP_MOMENTS[@]}"; do
    if [ "$f" = "$k" ]; then keep=true; break; fi
  done
  if [ "$keep" = false ]; then rm -f "$f"; fi
done
echo "Moments: kept ${#KEEP_MOMENTS[@]} files"

# For contacts/ directory - keep only specific files
cd "$SRC/contacts"
KEEP_CONTACTS=(
  DetectDeletedFriends.kt
  DisplayGroupMemberMessages.kt
  RoundAvatars.kt
)
for f in *.kt; do
  keep=false
  for k in "${KEEP_CONTACTS[@]}"; do
    if [ "$f" = "$k" ]; then keep=true; break; fi
  done
  if [ "$keep" = false ]; then rm -f "$f"; fi
done
echo "Contacts: kept ${#KEEP_CONTACTS[@]} files"

# For payment/ - keep all (10 files)
echo "Payment: keeping all files"

# For system/ - keep only specific files
cd "$SRC/system"
KEEP_SYSTEM=(
  AutoApproveDeviceLogin.kt
  AutoCleanCache.kt
  DisableHighBrightness.kt
  DisableHostHotUpdates.kt
  ForceTabletMode.kt
  HideModuleFromAppList.kt
  PowerSaver.kt
  PreventModuleDataDeletion.kt
  PreventXposedDetection.kt
  QrCodeRecord.kt
  RemoveArticleAds.kt
  RemoveQrCodeScanLimit.kt
  UseLegacyWalletViewInMePage.kt
)
for f in *.kt; do
  keep=false
  for k in "${KEEP_SYSTEM[@]}"; do
    if [ "$f" = "$k" ]; then keep=true; break; fi
  done
  if [ "$keep" = false ]; then rm -f "$f"; fi
done
echo "System: kept ${#KEEP_SYSTEM[@]} files"

# For miniapps/ - keep only 4 files
cd "$SRC/miniapps"
KEEP_MINI=(
  RemoveMenuLimits.kt
  RemoveSplashAds.kt
  RemoveVideoAds.kt
  SpoofHostVersion.kt
)
for f in *.kt; do
  keep=false
  for k in "${KEEP_MINI[@]}"; do
    if [ "$f" = "$k" ]; then keep=true; break; fi
  done
  if [ "$keep" = false ]; then rm -f "$f"; fi
done
echo "Miniapps: kept ${#KEEP_MINI[@]} files"

# Also delete AutomationSettings.kt in items root (if present)
rm -f "$SRC/../AutomationSettings.kt" 2>/dev/null
echo "Root items: cleaned up"

echo ""
echo "=== ALL DONE ==="
find "$SRC" -name '*.kt' | wc -l
