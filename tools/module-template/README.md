# Tanuki Magisk module template

Copy this directory, edit `module.prop`, add your files, run `./pack.sh`, then flash the resulting `module-template.zip` from the manager (Modules → Install from storage) or from recovery. The zip must keep `module.prop` at its root and `META-INF/` for recovery flashing.

Everything in the [official developer guides](https://topjohnwu.github.io/Magisk/guides.html) applies unchanged. This page is the complete reference for the layout: the standard content, the Kitsune Magisk 27.2 directories this fork restores (`boot-completed.sh`, `root/<partition>/`, `early-mount/`), and how to test a module.

## Legacy Kitsune modules

Modules written for Kitsune Magisk keep installing: during module installation the shell exports `MAGISK_VER` with both the `-tanuki` and the legacy `-kitsune` marker, so a `customize.sh` that sets `REQUIRED_KITSUNE_MAGISK=true` and ships the old `kitsune.sh` guard passes. A legacy module may rely only on the Kitsune 27.2 feature set this fork backported (the layout documented on this page); new modules keep using `REQUIRED_TANUKI_MAGISK` and the helpers in `tanuki.sh`.

## Files

| Path | Purpose |
|------|---------|
| `module.prop` | module identity: id, name, version, author, description |
| `customize.sh` | installer hook; sets `REQUIRED_TANUKI_MAGISK`, reads `SULISTMODE`, calls both checks |
| `tanuki.sh` | installer helpers: `check_if_need_tanuki`, `check_valid_module` |
| `META-INF/com/google/android/update-binary` | classic recovery installer |
| `META-INF/com/google/android/updater-script` | `#MAGISK` marker |
| `post-fs-data.sh` | script run at post-fs-data (empty placeholder, removed if left empty) |
| `service.sh` | late-start service script (empty placeholder, removed if left empty) |
| `boot-completed.sh` | script run after the boot completes (empty placeholder, removed if left empty) |
| `system.prop` | property overrides applied at boot (empty placeholder, removed if left empty) |
| `sepolicy.rule` | commented example; rules are applied by the engine at boot |
| `webroot/` | Module WebUI example using the `window.ksu` bridge |
| `zygisk/` | drop `zygisk/<abi>.so` here to be loaded into every process |
| `root/system/` | placeholder for the per-partition layout, see below |
| `early-mount/initrc.d/example.rc` | commented example, see below |
| `pack.sh` | packs this directory into `module-template.zip` |

Directories the template does not ship can be created when you need them:
`system/` (systemless overlay), `root/vendor/`, `root/product/`, `root/system_ext/`.

## When each script runs

All module scripts run as root with the Magisk busybox `sh`, on every boot, for every enabled module (`disable`, `remove` and `update` modules are skipped).
`ASH_STANDALONE=1` is set, `PATH` includes the Magisk tmp directory, and `ZYGISK_ENABLED=1` when Zygisk is on.
Output goes to the Magisk log (`/cache/magisk.log`, logcat tag `Magisk`).

| Script | Stage | Notes |
|--------|-------|-------|
| `post-fs-data.sh` | post-fs-data, before Zygote starts | blocking: the boot waits for it, and it is killed after 40 s |
| `service.sh` | late-start service | runs in the background, no timeout |
| `boot-completed.sh` | after the system reports boot completed | runs in the background like `service.sh` |

Early mount (`early-mount/`) is applied even earlier, by `magiskinit`, before any of these scripts run.

## Standard content

- `system/` : systemless overlay of the read-only partitions. Files are
  bind-mounted over the real ones at boot; nothing on disk is modified.
- A `.replace` file inside a `system/` directory replaces that whole
  directory instead of merging into it.
- `post-fs-data.sh`, `service.sh`, `boot-completed.sh` : see the table
  above.
- `system.prop` : `key=value` lines loaded as properties at boot.
- `sepolicy.rule` : one SELinux statement per line, applied by the engine
  at boot; `#` lines are skipped.
- `webroot/` : a Module WebUI page served by the manager, with the
  `window.ksu` bridge (`exec`, `spawn`, `moduleInfo`, `toast`, `exit`,
  `fileOutputStream`). See `webroot/index.js` for a working example.
- `zygisk/<abi>.so` : a Zygisk library loaded into every process by the
  built-in Zygisk runtime.
- `skip_mount` : creates an empty file with this name to keep the module
  installed but skip all its mounts.

## `boot-completed.sh`

Use it for work that needs a fully booted system: querying system services, starting a long-lived helper, a first-boot-only setup step.
It fires on every boot; guard one-shot work with a marker file:

```sh
MODDIR=${0%/*}
if [ ! -f "$MODDIR/first-boot.done" ]; then
  # ... one-time setup ...
  touch "$MODDIR/first-boot.done"
fi
```

> [!WARNING]
> There is no ordering between modules' scripts: do not assume another module's `boot-completed.sh` has already run.

## `root/<partition>/`

`root/` is the explicit per-partition layout restored from Kitsune Magisk 27.2. Files below it are mounted exactly as if they sat under the matching partition inside `system/`:

| Module path | Mounted at |
|-------------|------------|
| `root/system/etc/foo.conf` | `/system/etc/foo.conf` |
| `root/vendor/etc/foo.conf` | `/vendor/etc/foo.conf` |
| `root/product/...` | `/product/...` |
| `root/system_ext/...` | `/system_ext/...` |

The equivalent `system/vendor/etc/foo.conf` inside `system/` keeps working; `root/` only makes the target partition explicit and matches the layout modules already use elsewhere.
The files are bind-mounted at boot like any other module content, so nothing on the real partition changes.
`skip_mount` disables `root/` too.

## `early-mount/`

Files under `early-mount/` are copied at boot into a tmpfs by `magiskinit`, **before the root filesystem is patched**, and bind-mounted onto the real system partitions. This is for files a system service needs before `post-fs-data`. Content here is visible earlier than anything a
normal module mount can offer.

The layout mirrors the system tree, including the secondary partitions:

```text
early-mount/
  system/
    etc/...            -> /system/etc/...
    vendor/...         -> /vendor/... (only if /vendor is a separate mount)
    product/...        -> /product/...
    system_ext/...     -> /system_ext/...
```

Things to know:

> [!NOTE]
> - It needs a writable **preinit partition** (`/data`, `/cache`, `/metadata`, `/klogdump` or `/persist`, picked by the engine). On a device without one the early mount is skipped and logged as `empty mount dir`; the module still installs and boots normally.
> - Files are copied into a tmpfs, which is RAM: keep it small.
> - Mounting is read-only and happens at boot: editing the module applies at the next boot.
> - A module installed from the manager has its `early-mount/` tree refreshed by the daemon at boot-complete, so it takes effect on the **next** boot (the same timing as `sepolicy.rule`). Flashing the module from the installer or recovery copies it immediately.
> - Disabling or removing the module drops its `early-mount/` tree at the next boot; nothing is left behind.

### `early-mount/initrc.d/*.rc`

Any `*.rc` file under `early-mount/initrc.d/` is injected into the patched `init.rc` at boot, so it is parsed by init like a built-in rc file.
`${MAGISKTMP}` in the content is replaced with the Magisk tmp directory.
They are read from the preinit partition copy and never deleted.

`initrc.d/example.rc` is comments only: it is injected as-is and does
nothing. Add your own definitions next to it. An rc syntax error can
break the boot: test with Magisk's Safe Mode at hand.

## Testing a module

1. Run `./pack.sh` (regenerates `module-template.zip` and prints its SHA-256).
2. Flash the zip from the manager (Modules → Install from storage) or from recovery.
3. Check the install: the module directory appears under `/data/adb/modules/<id>`, and the stage output is in the Magisk log.
4. After a reboot, check `adb shell su -c 'cat /cache/magisk.log'` for the script lines (`exec [post-fs-data.sh]`, `exec [service.sh]`, `exec [boot-completed.sh]`) and the mount lines, then look at the mounted tree with `adb shell su -c 'mount'`.
5. Recovery: if a module breaks the boot, Safe Mode disables all modules; from a working boot `magisk --remove-modules` (or the manager's Remove) uninstalls them.

## Reference

- Module guide: <https://topjohnwu.github.io/Magisk/guides.html>
- Zygisk module sample: <https://github.com/topjohnwu/zygisk-module-sample>
- This fork's module notes: [docs/tanuki-modules.md](../../docs/tanuki-modules.md)
