# Building Tanuki Magisk

The toolchain and the general flow are official Magisk's: **JDK 25** (`build.py` rejects older ones), an Android SDK, an NDK and the Rust toolchain, set up as in the [upstream build documentation](https://topjohnwu.github.io/Magisk/build.html).
This page only covers what the fork adds on top.

## The flow

```sh
git submodule update --init      # xz-embedded, once per checkout
./build.py gen                   # regenerate the IDE/bridge files after native edits
./build.py all                   # native binaries, Compose app, test app
./build.py app-legacy            # the legacy manager, not part of all
```

`./build.py -r all` / `-r app-legacy` builds release variants; `-c
<path/to/config.prop>` points the build at a specific configuration file.
