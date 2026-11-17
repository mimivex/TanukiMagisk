# zygisk/

Drop a `zygisk/<abi>.so` library here (for example `zygisk/arm64-v8a.so`)
to have Zygisk load it into every process at boot, together with its
companion process library where the module uses one.

Reference implementation, entry symbol and build script:
[topjohnwu/zygisk-module-sample](https://github.com/topjohnwu/zygisk-module-sample)

This fork ships the stock Zygisk runtime and mount model: nothing under
`zygisk/` is specific to Tanuki Magisk.
