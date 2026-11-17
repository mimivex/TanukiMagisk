# customize.sh — installer hook, sourced by the Magisk installer with
# $MODPATH already populated. Read the module guide first:
# https://topjohnwu.github.io/Magisk/guides.html
#
# To extract the files yourself, set SKIPUNZIP=1 before the extraction is
# done automatically, then unpack what you need. META-INF is always excluded.
# SKIPUNZIP=1
# ui_print "- Unpacking"
# unzip -o "$ZIPFILE" 'system/*' -d "$MODPATH" >&2

# Helpers shared by every Tanuki template: feature guard and empty-module
# check. Keep this file next to customize.sh in the zip root.
. "$MODPATH/tanuki.sh"

# Abort on a non-Tanuki Magisk even when the module carries no Tanuki-only
# content. Set to true only if your module truly needs the fork.
REQUIRED_TANUKI_MAGISK=false

# The Tanuki installer exports SULISTMODE (true/false) so installers can
# adapt to the hiding mode the device runs in. In SuList mode root access is
# list-based: only apps explicitly allowed get a su prompt; MagiskHide mode
# uses the deny list instead.
if [ "$SULISTMODE" = true ]; then
  ui_print "- SuList mode is active (root access is list-based)"
else
  ui_print "- MagiskHide mode is active (root access is prompt-based)"
fi

# Wipe the empty placeholder scripts before the module is sealed; then abort
# if the module would install with nothing in it.
check_if_need_tanuki
check_valid_module

# Supported content:
#   system/            systemless overlay of the read-only partitions
#   root/<partition>/  explicit per-partition layout (system, vendor,
#                      product, system_ext), same effect as under system/
#   early-mount/       files magiskinit mounts before the rootfs is patched
#   early-mount/initrc.d/*.rc   snippets injected into init.rc at boot
#   post-fs-data.sh    script run in post-fs-data, before the boot completes
#   service.sh         late-start service script
#   boot-completed.sh  script run after the system reports boot completed
#   system.prop        property overrides applied at boot
#   sepolicy.rule      SELinux rules applied at magiskinit time
#   webroot/           Module WebUI page (index.html), served by the manager
#   zygisk/            native library loaded into every process by Zygisk
#
# The full reference is README.md next to this file.

# Write your own install-time logic below.

# END
