![Tanuki Magisk](docs/images/tanuki_logo.png#gh-dark-mode-only)
![Tanuki Magisk](docs/images/tanuki_logo_dark.png#gh-light-mode-only)

# Tanuki Magisk

[![GitHub stars](https://img.shields.io/github/stars/mimivex/TanukiMagisk?style=flat-square)](https://github.com/mimivex/TanukiMagisk/stargazers)&nbsp;&nbsp;&nbsp;[![GitHub forks](https://img.shields.io/github/forks/mimivex/TanukiMagisk?style=flat-square)](https://github.com/mimivex/TanukiMagisk/forks)&nbsp;&nbsp;&nbsp;[![GitHub downloads](https://img.shields.io/github/downloads/mimivex/TanukiMagisk/total?style=flat-square)](https://github.com/mimivex/TanukiMagisk/releases)

Tanuki Magisk carries forward the legacy of Kitsune Magisk (formerly Magisk Delta), while tracking the latest stable Magisk branch and incorporating additional fixes and quality-of-life improvements.

> [!WARNING]
> Tanuki is experimental. I take no responsibility if you experience data loss or worse. By downloading and installing this software, you accept doing so at your own risk.

## Features

Tanuki includes every feature of the official Magisk release, plus Kitsune Mask's own, and more.

### From Magisk

- **MagiskSU**: Provide root access for applications
- **Magisk Modules**: Modify read-only partitions by installing modules
- **MagiskBoot**: The most complete tool for unpacking and repacking Android boot images
- **Zygisk**: Run code in every Android applications' processes

### From Kitsune

- **MagiskHide**: the original Magisk hiding engine
- **SuList mode**: only whitelisted processes can see root
- **Manager app hiding**: dropped from Magisk, restored in Tanuki
- **Kitsune module support**: see the [module documentation](docs/tanuki-modules.md)
- **SELinux guard**: support SELinux-less systems (e.g. Waydroid)

### What's new in Tanuki

- **WebUI support**: embedded KernelSU-like web interfaces
- **Legacy UI support**: the legacy app is still shipped alongside the modern Compose app
- **Tanuki theme**: a new color theme for both light and dark modes
- **HyperOS support** (experimental): an opt-in `hyos_spawner` shim for the built-in Zygisk on HyperOS ROMs ([details](docs/hyos_spawner.md))
- **Vivo support** (experimental): the boot patcher hexpatches the vivo `do_mount_check` in the kernel and the engine mounts `/system` as `/syswxl`
- **Stock CLI compatibility**: `magisk --denylist ...` keeps upstream's verbs and always addresses the `denylist` table (in MagiskHide mode that is the live list, unlike Kitsune that used its own `hidelist`)
- **More UI localizations**: see the [Translations](#translations) section
- **Smaller fixes**: some smaller fixes and quality-of-life quirks to enhance the user experience


## Installation

Android 7.0 Nougat or later is required.
<br>A **full backup of your data** is always recommended to avoid any potential data loss.

### Start clean

**A fresh install is the recommended path, and the one that gets the least
trouble.**<br>Tanuki patches and flashes boot images exactly like official
Magisk, so the steps are simply the same: unlock the bootloader,
obtain the stock boot image (`boot.img`, or `init_boot.img` on devices that
have it), patch it with the app, flash the result, reboot. The [official
installation guide](https://topjohnwu.github.io/Magisk/install.html) walks
through every step and applies unchanged; start from there.<br>On a device with
a recovery and a ramdisk you can also rename `magisk-*.apk` to `.zip` and
flash that.

[!NOTE]
Installing over another root solution like Apatch or KernelSU works, but it is a migration: the old daemon, its manager and its settings have to be replaced or cleaned up along the way.

### Migrating from another Magisk

If a Magisk flavor is already installed, **grant the Tanuki manager app root first, then reopen it**: the handover needs a working root grant from the existing installation.

1. Open Tanuki and **Install** → **Direct Install (Recommended)**.
2. Uninstall the other Magisk manager APKs.
3. Reboot.

Modules, their data and `/data/adb` stay in place: only the daemon and the
manager are replaced.

- **From official Magisk**: the daemon is replaced in place. The manager is a new install rather than an update, because Tanuki has its own package id and its own signing key; uninstalling the old manager is a step, not an option.
- **From Kitsune Magisk 27.2**: the same flow, and the database is compatible as it is: policies, modules and the SuList/MagiskHide mode setting carry over. Kitsune kept its MagiskHide list in `hidelist` and its SuList in `sulist`; Tanuki reads `sulist` untouched, and on the first boot imports `hidelist` into `denylist` once, which is the table MagiskHide mode reads. Both lists come across.


## Downloads

Downloads belong to the [releases section](https://github.com/mimivex/TanukiMagisk/releases).

## Documentation

You can find the official Magisk documentation and this fork documentation listed [here](docs/README.md).

A ready-to-use module for this engine (and stock Magisk) lives in `tools/module-template/`: copy it, edit `module.prop`, add files, run `./pack.sh`, flash the zip.<br>It covers every stage this fork implements, including `boot-completed.sh`, `root/<partition>/` and `early-mount/`.

## Building

Needs **JDK 25**, an Android SDK/NDK and the Rust toolchain ([upstream
build documentation](https://topjohnwu.github.io/Magisk/build.html)).
Initialize the submodule once, then build:

```sh
git submodule update --init
./build.py all        # native binaries, Compose app, test app
./build.py app-legacy # the legacy app, not included in all
```

## Translations

Strings live in `app/core/src/main/res/values/strings.xml` and in the `app/apk` / `app/apk-legacy` modules; translations go in the matching `values-<lang>/strings.xml`.

[!NOTE]
>Many translations in this fork were produced with AI. In any future upstream merge, translations supplied by users will be preferred for the Compose application.
> Therefore I recommend [contributing to the official project translations](https://github.com/topjohnwu/Magisk#translation-contributions).<br>
> String fixes and new localizations for the legacy application are welcome.

## Bug reports

[!NOTE]
A disclaimer is due here: Tanuki is **not actively supported** and is maintained only as my free time allows. As much as I would like to help, I cannot guarantee that I will ever be able to address any reported issue.

If you open an issue, it must come from a **debug build**, and you must first confirm the problem does not also happen on the same version of the [official Magisk](https://github.com/topjohnwu/Magisk); if it does, it belongs upstream, not here.

- **Installation problems:** send the boot image and install logs.
- **Tanuki issues:** send boot logcat or dmesg.
- **Crashes:** send logcat captured at the crash.

Report at [GitHub Issues](https://github.com/mimivex/TanukiMagisk/issues).


## Credits

Tanuki would not exist without the work of some amazing people:

- [topjohnwu](https://github.com/topjohnwu) — [Magisk](https://github.com/topjohnwu/Magisk).
- [HuskyDG](https://github.com/HuskyDG) — [Kitsune Magisk](https://github.com/HuskyDG/magisk-files) (thanks to [1q23lyc45](https://github.com/1q23lyc45/KitsuneMagisk) for keeping the code alive).
- [Seyud](https://github.com/Seyud) — [WeaveMask](https://github.com/Seyud/WeaveMask) for the su-notify timeout
- [5ec1cff](https://github.com/5ec1cff) and [KOWX712](https://github.com/KOWX712) —  [KsuWebUIStandalone](https://github.com/KOWX712/KsuWebUIStandalone) for the Module WebUI


## Donations

Tanuki Magisk is free and will always be. Please consider supporting [the original project](https://www.patreon.com/cw/topjohnwu), which Tanuki is based on.<br>
If Tanuki has been useful to you and you'd like to show your appreciation for my work, you can [buy me a coffee](https://ko-fi.com/X0K029DTFX).

## License

GPL-3.0 or later, like upstream: [license
text](https://github.com/topjohnwu/Magisk/blob/master/LICENSE).<br>Third-party
components: [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
