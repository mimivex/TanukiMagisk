## HyperOS (hyos_spawner)

HyperOS starts apps from `/system_ext/bin/hyos_spawner` instead of zygote, and that process never loads the ART native bridge, so the built-in Zygisk is never initialized in app processes there. The shim stages a small loader in `/dev` and mirrors the spawner's service definition into the rootfs overlay with a `setenv LD_PRELOAD`: the loader pulls the Magisk binary in as a shared object and runs the native-bridge entry, which injects the built-in Zygisk.

`hyos_spawner` is specific to HyperOS 2.x and later: it is absent on HyperOS 1.x, present on HyperOS 2.x, 3.x, and 4.x (4.x moves the runtime to Rust, `libhyper_os_*`, plus a Flutter engine, `libhyper_os_flutter.so`; see [HyperOS3-Rust-Bridge](https://github.com/kakashi-1v1/HyperOS3-Rust-Bridge) and [HyperOSDockSink](https://github.com/chasers7ar-prog/HyperOSDockSink), a ZygiskNext module for HyperOS 4 built on `hyos_spawner`). On a HyperOS 1.x device this whole mechanism stays dormant.

- **When to enable it:** only if you use the built-in Zygisk on a HyperOS 2.x or later device (the shim serves the built-in engine only).
- **How to enable it:** as root, `touch /data/adb/hyos_shim_enable`, turn the built-in Zygisk toggle on, and reboot. At boot magiskinit checks the shim against the spawner's ELF class, stages it, and never overrides an `LD_PRELOAD` the platform already sets; the kernel log (`dmesg`) shows `HyOS: preloading the Zygisk shim in [...]`. The marker itself is checked by the shim when the spawner starts, once `/data` is mounted; without it the shim exits immediately.
- **How to roll back:** delete `/data/adb/hyos_shim_enable` and reboot.
- **Why it needs the marker:** the staged preload must stay inert unless requested, so the shim only loads the Magisk binary (and with it the injection) when the marker is present; a helper file staged in `/dev` grants the spawner's SELinux domain read access to it.

> [!WARNING]
> This feature is *experimental*. Arming and injection are verified on HyperOS 1.x only by absence (the shim stays inert); running them on HyperOS 2.x, 3.x and 4.x hardware has not been verified on a device yet.
