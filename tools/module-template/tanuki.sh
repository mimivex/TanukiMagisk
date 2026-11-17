# tanuki.sh — installer helpers for Tanuki Magisk modules.
# Sourced by customize.sh; runs inside the Magisk installer shell.

# Aborts when the module needs the Tanuki engine but the installer is a
# stock Magisk build. Requires REQUIRED_TANUKI_MAGISK (set in customize.sh).
check_if_need_tanuki() {
  if [ "$MAGISK_VER" != *"-tanuki"* ] && [ "$REQUIRED_TANUKI_MAGISK" = true ]; then
    ui_print "*********************************************************"
    ui_print "! This module requires Tanuki Magisk"
    abort    "*********************************************************"
  fi
}

# Removes the empty placeholder files, then aborts when the module carries
# no installable content at all.
check_valid_module() {
  for s in post-fs-data.sh service.sh boot-completed.sh sepolicy.rule system.prop
  do
    [ "$(stat -c '%s' "$MODPATH/$s")" = "0" ] && rm -rf "$MODPATH/$s"
  done
  for m in system root early-mount webroot zygisk \
           post-fs-data.sh service.sh boot-completed.sh sepolicy.rule system.prop
  do
    [ -e "$MODPATH/$m" ] && return
  done
  abort "! Nothing to install, abort"
}
