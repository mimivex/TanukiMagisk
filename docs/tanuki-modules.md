# Tanuki Magisk modules

The module contract is official Magisk's: a zip with `module.prop` at its root, the standard script set and the classic `META-INF` installer for recovery flashing. Everything in the [official developer guides](https://topjohnwu.github.io/Magisk/guides.html) applies unchanged.
This page only lists what the fork adds or does differently.

A ready-to-use starting point lives in [`tools/module-template/`](../tools/module-template/README.md).

## What this fork understands

- `system/` systemless overlay, `post-fs-data.sh`, `service.sh`, `boot-completed.sh`, `system.prop`, `skip_mount` : identical to official Magisk v31, plus the `boot-completed.sh` stage that Kitsune Magisk 27.2 had and v31 upstream dropped.
- `root/<partition>/` : the explicit per-partition layout (`system`, `vendor`, `product`, `system_ext`): files mount exactly as if they sat under the matching partition inside `system/`.
- `early-mount/` : files copied by `magiskinit` into a tmpfs before the root filesystem is patched and bind-mounted onto the real partitions. Needs a writable preinit partition (`/data`, `/cache`, `/metadata`, …).
- `early-mount/initrc.d/*.rc` : rc snippets injected into the patched `init.rc` at boot; `${MAGISKTMP}` is expanded. They are read from the preinit partition copy and never deleted.
- `zygisk/` : loaded by the stock Zygisk runtime (built in, disabled by default in Settings).
- `webroot/` : the Module WebUI, described below.

A module installed from the manager has `early-mount/` and `sepolicy.rule` refreshed by the daemon at boot-complete, so changes take effect on the next boot; a flash copies them immediately. The [template guide](../tools/module-template/README.md) has the complete layout and examples.

## `SULISTMODE`

While installing a module the installer exports `SULISTMODE`:

- `true` → the device is in SuList mode: root access is list-based, only the apps on the SuList get a root prompt.
- `false` → MagiskHide mode: root access is prompt-based and hidden apps come from the deny list.

A `customize.sh` can branch on it:

```sh
if [ "$SULISTMODE" = true ]; then
  ui_print "- SuList mode is active (root access is list-based)"
fi
```

This flag is the only install-time contract the fork adds; it exists so a module can adapt its own behaviour, and the template's `customize.sh` shows the intended use.

## Module WebUI (`webroot/`)

A module that ships a `webroot/` directory gets a **WebUI** entry in the manager. The page is served from the module directory at `https://mui.kernelsu.org/index.html` and the manager injects a `window.ksu` bridge:

| Member | Signature | Notes |
|--------|-----------|-------|
| `ksu.exec` | `exec(cmd)` / `exec(cmd, callbackName)` / `exec(cmd, optionsJson, callbackName)` | runs as root; the callback is a **global function name**, evaluated as `callback(code, stdout, stderr)` when the command finishes |
| `ksu.spawn` | `spawn(command, argsJson, optionsJson, callbackName)` | the callback object receives `stdout`/`stderr` `data` events and one `exit` event |
| `ksu.moduleInfo` | `moduleInfo()` | returns a JSON string with `id` and `moduleDir` |
| `ksu.toast` | `toast(message)` | short toast in the manager |
| `ksu.exit` | `exit()` | closes the WebUI activity |
| `ksu.fileOutputStream` | `open`, `write`, `flush`, `close` | streaming writes to root files |
| `ksu.listPackages` | `listPackages(type)` | JSON array of package names; `"system"` and `"user"` filter the list, anything else (including `null`) returns every package |
| `ksu.getPackagesInfo` | `getPackagesInfo(packageNamesJson)` | JSON array, one object per requested name, in the requested order: `packageName`, `versionName`, `versionCode`, `appLabel`, `isSystem`, `uid`; an unknown package comes back as `{packageName, error}` instead of failing the whole call |
| `ksu.fullScreen` | `fullScreen(enable)` | hides or restores the system bars and switches edge-to-edge on or off |
| `ksu.enableEdgeToEdge` | `enableEdgeToEdge(enable)` | the same edge-to-edge switch without touching the bars; `enableInsets` is an alias kept for pages written against older clients |

A page can also ask for the generated assets the reference client serves:

- **`internal/colors.css`** → the Material 3 palette as CSS variables (`--primary`, `--surface`, `--surfaceContainer`, …45 in total). On Android 12 and later they come from the platform's dynamic colors, so the page follows the wallpaper; below that, and whenever a role is missing, a static Material 3 palette is used.
- **`internal/insets.css`** → importing it is also the request for edge-to-edge: the manager stops padding the window itself, and the page receives `--safe-area-inset-top/right/bottom/left` in dp, refreshed on every configuration change. Before the import those values are `0px`.
- **`ksu://icon/<packageName>`** → a PNG of the app's launcher icon, scaled to 512 px. An unknown package serves nothing and the page keeps its own placeholder.

For files, pages get a second bridge, `window.ksu_download`: the manager injects a script that intercepts clicks on `<a download>` (and programmatic `HTMLAnchorElement.click`) and sends blob, `data:` and same-origin payloads through `ksu_download.save(base64, fileName, mimeType)`, which opens the system create-document picker, up to 16 MB, while any other URL goes to `ksu_download.download(url, contentDisposition, mimeType)` and the system DownloadManager.

There is no `internal/ksu.js` wrapper: pages call the bridge directly, as in the template's `webroot/index.js`. Navigation away from `mui.kernelsu.org` is handed to the system browser, so external assets cannot reach the bridge.

## `sepolicy.rule`

Per-module SELinux rules, one statement per line, applied by the engine at boot (`magiskinit` time). Lines starting with `#` are skipped. The rules survive module updates and recovery installs: the installer copies them into the preinit directory, and the daemon re-creates that directory from the installed modules at boot if it is missing. An empty rule file is removed at install time and costs nothing.

## What this fork adds

Beyond the official contract the fork restores three Kitsune Magisk 27.2 directories (`boot-completed.sh`, `root/<partition>/`, `early-mount/`) and the `SULISTMODE` variable above.

At boot the engine runs `post-fs-data.sh`, `service.sh` and `boot-completed.sh`, in that order; `action.sh` is started by the manager when you tap Action. `tanuki.sh` is not one of these hooks: it is sourced by `customize.sh` during installation and only holds installer helpers.
