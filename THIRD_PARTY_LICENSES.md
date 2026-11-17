# Third-party licenses and provenance

This tree is a fork of Magisk and is released under the GNU General Public License v3.0, the same license as upstream Magisk (see `LICENSE`). The great majority of the
code is upstream Magisk or Kitsune Magisk's own work.

Some parts carry code (or a port of it) from other projects. This file lists
them with origin, license and the files involved.

## 1. Kitsune Magisk deny engine by HuskyDG

- **Origin**: Kitsune Magisk 27.2, by [HuskyDG](https://github.com/HuskyDG); releases were published at <https://github.com/HuskyDG/magisk-files>, now deleted. The 27.2 source this port was taken from is the [maintained copy kept by 1q23lyc45](https://github.com/1q23lyc45/KitsuneMagisk), which still carries the code and the releases: thanks are due for keeping it available.
- **License**: GPL-3.0
- **Files**: `native/src/core/deny/ptrace.cpp`, `revert.cpp`, `deny.hpp`, `utils.cpp`, `cli.cpp`, and the integration into the daemon, boot stages and the `magiskhide` applet.
- **What**: the ptrace-based, synchronous MagiskHide/SuList engine, ported onto Magisk v31, with SuList as the whitelist mode, the recommended-list seeding, tombstones for explicit removals and the stock database schema.

## 2. Module WebUI by 5ec1cff / KOWX712 (KsuWebUIStandalone)

- **Origin**: [KsuWebUIStandalone](https://github.com/KOWX712/KsuWebUIStandalone), originally by [5ec1cff](https://github.com/5ec1cff), currently maintained by [KOWX712](https://github.com/KOWX712).
- **License**: GPL-3.0
- **Files**: `app/core/src/main/java/com/topjohnwu/magisk/core/ui/webui/ModuleWebUIActivity.kt`, `WebViewInterface.kt`, `RemoteFsPathHandler.kt`, `WebUIPackages.kt`, `WebUIColors.kt`, `WebUIIcons.kt`, `Insets.kt`, `DownloadInterface.kt`.
- **What**: the WebView host activity, the KernelSU-compatible `window.ksu` bridge (`exec`, `spawn`, `listPackages`, `getPackagesInfo`, `moduleInfo`, `toast`, `exit`, `fileOutputStream`, `fullScreen`, `enableEdgeToEdge`, `enableInsets`), the `window.ksu_download` bridge with its injected download script, the generated `internal/colors.css` and `internal/insets.css` documents, the `ksu://icon/<package>` handler and the assets loader that serves a module's `webroot` over the virtual `https://mui.kernelsu.org` origin.

## 3. su-notify timeout by Seyud (WeaveMask)

- **Origin**: [WeaveMask](https://github.com/Seyud/WeaveMask) by [Seyud](https://github.com/Seyud)
- **License**: GPL-3.0
- **Files**: `native/src/core/su/connect.rs` (the bounded poll on the policy FIFO) and `app/core/src/main/java/com/topjohnwu/magisk/core/Provider.kt`.
- **What**: a pending su request gives up after a bounded wait instead of blocking forever when the manager is frozen or never answers, so the request resolves as denied and the waiting processes no longer pile up.

## 4. MimeUtil from AOSP / Chromium

- **Origin**: the Android Open Source Project / Chromium MIME table.
- **License**: Apache-2.0 (the license header is retained in the file).
- **File**: `app/core/src/main/java/com/topjohnwu/magisk/core/ui/webui/MimeUtil.kt`.

## Upstream base

Everything not listed above (the Magisk daemon, su, magisk policy, the boot patch, the manager UI shell, the build scripts) is upstream [Magisk](https://github.com/topjohnwu/Magisk) by [topjohnwu](https://github.com/topjohnwu), GPL-3.0, see `LICENSE`.
