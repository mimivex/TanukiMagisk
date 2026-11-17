#include <set>
#include <sys/mount.h>
#include <sys/sysmacros.h>
#include <libgen.h>

#include <base.hpp>
#include <consts.hpp>

#include "init.hpp"

using namespace std;

struct devinfo {
    int major;
    int minor;
    char devname[32];
    char partname[32];
    char dmname[32];
};

static vector<devinfo> dev_list;

// When this boolean is set, this means we are currently
// running magiskinit on legacy SAR AVD emulator
bool avd_hack = false;

static void parse_device(devinfo *dev, const char *uevent) {
    dev->partname[0] = '\0';
    dev->dmname[0] = '\0';
    dev->devname[0] = '\0';
    parse_prop_file(uevent, [=](Utf8CStr key, Utf8CStr value) -> bool {
        if (key == "MAJOR")
            dev->major = parse_int(value);
        else if (key == "MINOR")
            dev->minor = parse_int(value);
        else if (key == "DEVNAME")
            strscpy(dev->devname, value.c_str(), sizeof(dev->devname));
        else if (key == "PARTNAME")
            strscpy(dev->partname, value.c_str(), sizeof(dev->partname));

        return true;
    });
}

void MagiskInit::collect_devices() const noexcept {
    char path[PATH_MAX];
    devinfo dev{};
    if (auto dir = xopen_dir("/sys/dev/block"); dir) {
        for (dirent *entry; (entry = readdir(dir.get()));) {
            if (entry->d_name == "."sv || entry->d_name == ".."sv)
                continue;
            sprintf(path, "/sys/dev/block/%s/uevent", entry->d_name);
            parse_device(&dev, path);
            sprintf(path, "/sys/dev/block/%s/dm/name", entry->d_name);
            if (access(path, F_OK) == 0) {
                auto name = rtrim(full_read(path));
                strscpy(dev.dmname, name.data(), sizeof(dev.dmname));
            }
            if (auto it = std::ranges::find_if(config.partition_map, [&](const auto &i) {
                return i.key == dev.devname;
            }); dev.partname[0] == '\0' && it != config.partition_map.end()) {
                // use androidboot.partition_map as partname fallback.
                strscpy(dev.partname, it->value.data(), sizeof(dev.partname));
            }
            dev_list.push_back(dev);
        }
    }
}

uint64_t MagiskInit::find_block(const char *partname) const noexcept {
    if (dev_list.empty())
        collect_devices();

    for (int tries = 0; tries < 3; ++tries) {
        for (auto &dev : dev_list) {
            const char *name;
            if (strcasecmp(dev.partname, partname) == 0)
                name = dev.partname;
            else if (strcasecmp(dev.dmname, partname) == 0)
                name = dev.dmname;
            else if (strcasecmp(dev.devname, partname) == 0)
                name = dev.devname;
            else
                continue;

            LOGD("Found %s: [%s] (%d, %d)\n", name, dev.devname, dev.major, dev.minor);
            return makedev(dev.major, dev.minor);
        }
        // Wait 10ms and try again
        usleep(10000);
        dev_list.clear();
        collect_devices();
    }

    // The requested partname does not exist
    return 0;
}

void MagiskInit::mount_preinit_dir() noexcept {
    if (preinit_dev.empty()) return;
    auto dev = find_block(preinit_dev.c_str());
    if (dev == 0) {
        LOGE("Cannot find preinit %s, abort!\n", preinit_dev.c_str());
        return;
    }
    xmknod(PREINITDEV, S_IFBLK | 0600, dev);
    xmkdir(MIRRDIR, 0);
    bool mounted = false;
    // First, find if it is already mounted
    std::string mnt_point;
    if (rust::is_device_mounted(dev, mnt_point)) {
        // Already mounted, just bind mount
        xmount(mnt_point.data(), MIRRDIR, nullptr, MS_BIND, nullptr);
        mounted = true;
    }

    // Since we are mounting the block device directly, make sure to ONLY mount the partitions
    // as read-only, or else the kernel might crash due to crappy drivers.
    // After the device boots up, magiskd will properly symlink the correct path at PREINITMIRR as writable.
    if (mounted || mount(PREINITDEV, MIRRDIR, "ext4", MS_RDONLY, nullptr) == 0 ||
        mount(PREINITDEV, MIRRDIR, "f2fs", MS_RDONLY, nullptr) == 0) {
        string preinit_dir = resolve_preinit_dir(MIRRDIR);
        string early_mnt_dir = resolve_early_mount_dir(MIRRDIR);
        // Create bind mount
        xmkdirs(PREINITMIRR, 0);
        xmkdirs(EARLYMNT, 0);
        if (access(preinit_dir.data(), F_OK)) {
            LOGW("empty preinit: %s\n", preinit_dir.data());
        } else {
            LOGD("preinit: %s\n", preinit_dir.data());
            xmount(preinit_dir.data(), PREINITMIRR, nullptr, MS_BIND, nullptr);
        }
        if (access(early_mnt_dir.data(), F_OK)) {
            LOGW("empty mount dir: %s\n", early_mnt_dir.data());
        } else {
            // Copy mount files to a tmpfs: files cannot be mounted directly
            // from the preinit partition, as that would make the partition
            // unable to mount itself later in the boot process.
            LOGD("early mount: %s\n", early_mnt_dir.data());
            xmount(EARLYMNTNAME, EARLYMNT, "tmpfs", 0, nullptr);
            cp_afc(early_mnt_dir.data(), EARLYMNT);
        }
        xumount2(MIRRDIR, MNT_DETACH);
    } else {
        PLOGE("Mount preinit %s", preinit_dev.c_str());
        // Do NOT delete the block device. Even though we cannot mount it here,
        // it might get formatted later in the boot process.
    }
}

bool MagiskInit::mount_system_root() noexcept {
    LOGD("Mounting system_root\n");

    // there's no /dev in stub cpio
    xmkdir("/dev", 0777);

    dev_t dev;
    do {
        // Try legacy SAR dm-verity
        dev = find_block("vroot");
        if (dev > 0)
            goto mount_root;

        // Try NVIDIA naming scheme
        dev = find_block("APP");
        if (dev > 0)
            goto mount_root;

        // Try normal partname
        char sys_part[32];
        sprintf(sys_part, "system%s", config.slot.data());
        dev = find_block(sys_part);
        if (dev > 0)
            goto mount_root;

        // Poll forever if rootwait was given in cmdline
    } while (config.rootwait);

    // We don't really know what to do at this point...
    LOGE("Cannot find root partition, abort\n");
    exit(1);

mount_root:
    xmknod("/dev/root", S_IFBLK | 0600, dev);
    xmkdir("/system_root", 0755);

    if (xmount("/dev/root", "/system_root", "ext4", MS_RDONLY, nullptr)) {
        if (xmount("/dev/root", "/system_root", "erofs", MS_RDONLY, nullptr)) {
            // We don't really know what to do at this point...
            LOGE("Cannot mount root partition, abort\n");
            exit(1);
        }
    }

    rust::switch_root("/system_root");

    // Make dev writable
    xmount("tmpfs", "/dev", "tmpfs", 0, "mode=755");
    mount_list.emplace_back("/dev");

    bool is_two_stage = access("/system/bin/init", F_OK) == 0;
    LOGD("is_two_stage: [%d]\n", is_two_stage);

    // For API 28 AVD, it uses legacy SAR setup that requires
    // special hacks in magiskinit to work properly.
    if (!is_two_stage && config.emulator) {
        avd_hack = true;
        // These values are hardcoded for API 28 AVD
        auto vendor_dev = find_block("vendor");
        xmkdir("/dev/block", 0755);
        xmknod("/dev/block/vde1", S_IFBLK | 0600, vendor_dev);
        xmount("/dev/block/vde1", "/vendor", "ext4", MS_RDONLY, nullptr);
    }

    return is_two_stage;
}

static bool is_symlink(const char *path) {
    struct stat st;
    return lstat(path, &st) == 0 && S_ISLNK(st.st_mode);
}

static void simple_mount(const string &sdir, const string &ddir = "") {
    auto dir = xopen_dir(sdir.data());
    if (!dir) return;
    for (dirent *entry; (entry = xreaddir(dir.get()));) {
        string src = sdir + "/" + entry->d_name;
        string dest = ddir + "/" + entry->d_name;
        if (access(dest.data(), F_OK) == 0 && !is_symlink(dest.data())) {
            if (entry->d_type == DT_LNK) continue;
            else if (entry->d_type == DT_DIR) {
                // Recursive
                simple_mount(src, dest);
            } else {
                LOGD("bind_mnt: %s <- %s\n", dest.data(), src.data());
                xmount(src.data(), dest.data(), nullptr, MS_BIND, nullptr);
            }
        }
    }
}

// A fresh tmpfs has its own st_dev, which the parent directory (the /data
// tmpfs) does not share. Used to skip the read-only remount when early
// mount was not set up (no preinit partition, or no early-mount.d yet).
static bool is_mount_point(const char *path) {
    struct stat st{}, parent{};
    string parent_path = string(path) + "/..";
    return stat(path, &st) == 0 && stat(parent_path.data(), &parent) == 0 &&
           st.st_dev != parent.st_dev;
}

static void early_mount() {
    // Per-module early mount trees copied into the preinit dir
    if (auto dir = xopen_dir(PREINITMIRR)) {
        for (dirent *entry; (entry = xreaddir(dir.get()));) {
            auto name = PREINITMIRR "/"s + entry->d_name;
            auto emnt = name + "/early-mount";
            if (access(emnt.data(), R_OK) == 0 &&
                access((name + "/disable").data(), F_OK) != 0 &&
                access((name + "/remove").data(), F_OK) != 0) {
                // Copy mount files to the tmpfs: they cannot be mounted
                // directly from the preinit partition.
                LOGD("Loading custom early mount patch: [%s]\n", emnt.data());
                cp_afc(emnt.data(), EARLYMNT);
            }
        }
    }
    if (is_mount_point(EARLYMNT))
        xmount(nullptr, EARLYMNT, nullptr, MS_RDONLY | MS_REMOUNT, nullptr);

    // TODO: support magic mount
    if (access(EARLYMNT "/system", F_OK) == 0)
        simple_mount(EARLYMNT "/system", "/system");
#define EARLY_MNT(part) \
    if (access(EARLYMNT "/system/" part, F_OK) == 0 && !is_symlink("/" part)) \
        simple_mount(EARLYMNT "/system/" part, "/" part);
    EARLY_MNT("vendor")
    EARLY_MNT("product")
    EARLY_MNT("system_ext")
}

void MagiskInit::setup_tmp(const char *path) noexcept {
    LOGD("Setup Magisk tmp at %s\n", path);
    chdir("/data");

    xmkdir(INTLROOT, 0711);
    xmkdir(DEVICEDIR, 0711);
    xmkdir(WORKERDIR, 0);

    mount_preinit_dir();
    early_mount();

    cp_afc(".backup/.magisk", MAIN_CONFIG);
    rm_rf(".backup");

    // Create applet symlinks
    for (int i = 0; applet_names[i]; ++i)
        xsymlink("./magisk", applet_names[i]);
    xsymlink("./magiskpolicy", "supolicy");

    xmount(".", path, nullptr, MS_BIND, nullptr);
    // Expose the early mount tmpfs at the same path inside the Magisk tmp
    // directory. When early_mount did not run, EARLYMNT does not exist and
    // there is nothing to expose.
    if (access(EARLYMNT, F_OK) == 0)
        xmount(EARLYMNT, (string(path) + "/" EARLYMNT).data(), nullptr, MS_BIND, nullptr);

    chdir(path);

    // Prepare worker
    xmount("magisk", WORKERDIR, "tmpfs", 0, "mode=755");

    // Use isolated devpts if kernel support
    if (access("/dev/pts/ptmx", F_OK) == 0) {
        xmkdirs(SHELLPTS, 0755);
        xmount("devpts", SHELLPTS, "devpts", MS_NOSUID | MS_NOEXEC, "newinstance");
        xmount(nullptr, SHELLPTS, nullptr, MS_PRIVATE, nullptr);
        if (access(SHELLPTS "/ptmx", F_OK)) {
            umount2(SHELLPTS, MNT_DETACH);
            rmdir(SHELLPTS);
        }
    }

    chdir("/");
}
