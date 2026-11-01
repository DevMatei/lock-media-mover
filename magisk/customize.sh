#!/system/bin/sh

ui_print " "
ui_print "  Lock Media Mover v1.2"
ui_print "  lockscreen player goes down, iOS style"
ui_print " "

API=$(getprop ro.build.version.sdk)
if [ -n "$API" ] && [ "$API" -lt 31 ]; then
  abort "  ! Android 12+ required (found API $API)"
fi
if [ -n "$API" ] && [ "$API" -lt 36 ]; then
  ui_print "  ! Built and tested against Android 16 source."
  ui_print "    Older versions may just do nothing."
fi

if [ -d /data/adb/lspd ] || [ -d /data/adb/modules/zygisk_lsposed ] \
   || [ -d /data/adb/modules/zygisk_vector ] || ls -d /data/adb/modules/*lsposed* >/dev/null 2>&1; then
  ui_print "  - LSPosed found"
else
  ui_print "  ! LSPosed NOT found. This module does nothing without it."
  ui_print "    Install LSPosed (Zygisk) first, then reboot."
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print " "
ui_print "  After reboot:"
ui_print "  1. LSPosed > Modules > Lock Media Mover > enable"
ui_print "  2. Tick System UI in its scope"
ui_print "  3. Reboot (or restart System UI)"
ui_print " "
ui_print "  Change position:"
ui_print "  - Magisk > Modules > Action, then Vol+/Vol-"
ui_print "  - or the WebUI (KernelSU, KsuWebUI, MMRL)"
ui_print " "
