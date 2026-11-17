// HyperOS injection shim.
//
// HyperOS starts application processes from /system_ext/bin/hyos_spawner
// instead of the standard zygote, and that process never loads the native
// bridge, so the built-in Zygisk is not initialized there. This library is
// LD_PRELOADed into the spawner alone: magiskinit mirrors the init rc of its
// service with a setenv (see init/rootdir.rs) and stages this shim together
// with the Magisk binary in /dev, labeled magisk_file so that the spawner's
// domain can read them. From inside the spawner it does what ART would do
// with ro.dalvik.vm.native.bridge: load the Magisk binary as a shared object
// and invoke the NativeBridgeItf entry point, which runs hook_entry().
//
// magiskinit arms this preload whenever hyos_spawner exists; the opt-in
// (/data/adb/hyos_shim_enable) is checked here, inside the spawner, because
// the marker lives on /data, which is not mounted yet when magiskinit runs.
// Without the marker this library is a no-op in every process, and LD_PRELOAD
// is always removed from the environment so exec'd descendants never load it.

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "zygisk-hyos"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Same layout as the vendored NativeBridgeCallbacks in core/zygisk/zygisk.hpp
struct native_bridge_callbacks {
    uint32_t version;
    void *padding[5];
    bool (*is_compatible_with)(uint32_t);
};

static int is_spawner(void) {
    char buf[256];

    ssize_t n = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (n > 0) {
        buf[n] = '\0';
        if (strcmp(buf, "/system_ext/bin/hyos_spawner") == 0)
            return 1;
    }

    // init may override argv[0]; fall back to its basename
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return 0;
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0)
        return 0;
    buf[n] = '\0';

    size_t len = strnlen(buf, (size_t) n);
    const char *base = buf;
    for (const char *p = buf; p < buf + len; ++p) {
        if (*p == '/')
            base = p + 1;
    }
    return len > 0 && strcmp(base, "hyos_spawner") == 0;
}

__attribute__((constructor))
static void hyos_shim_init(void) {
    // Never let an exec'd descendant inherit the preload
    unsetenv("LD_PRELOAD");

    if (!is_spawner())
        return;

    // The opt-in marker lives on /data, mounted by the time the spawner runs
    if (access("/data/adb/hyos_shim_enable", F_OK) != 0)
        return;

    LOGI("HyOS marker found, loading the Zygisk shim\n");

    static const char *const magisk_bins[] = {
        "/dev/hyos_magisk",
        "/debug_ramdisk/magisk",
        "/sbin/magisk",
    };

    void *handle = NULL;
    for (size_t i = 0; i < sizeof(magisk_bins) / sizeof(magisk_bins[0]); ++i) {
        if (access(magisk_bins[i], R_OK) != 0)
            continue;
        handle = dlopen(magisk_bins[i], RTLD_NOW | RTLD_GLOBAL);
        if (handle)
            break;
        LOGW("dlopen %s failed: %s\n", magisk_bins[i], dlerror());
    }
    if (handle == NULL) {
        LOGW("cannot load the Magisk binary, built-in Zygisk not injected\n");
        return;
    }

    const struct native_bridge_callbacks *itf =
        (const struct native_bridge_callbacks *) dlsym(handle, "NativeBridgeItf");
    if (itf == NULL || itf->is_compatible_with == NULL) {
        LOGW("NativeBridgeItf not found in the Magisk binary\n");
        return;
    }

    itf->is_compatible_with(itf->version);
    LOGI("built-in Zygisk injected into hyos_spawner\n");
}
