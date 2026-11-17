use crate::consts::{EARLYMNT, EARLYMNTNAME, MODULEMNT, MODULEROOT, MODULEUPGRADE, PREINITDEV, PREINITMIRR, WORKERDIR};
use crate::ffi::{get_magisk_tmp, resolve_early_mount_dir, resolve_preinit_dir, switch_mnt_ns};
use crate::resetprop::get_prop;
use base::{
    FsPathBuilder, LibcReturn, LoggedResult, MountInfo, ResultExt, Utf8CStr, Utf8CStrBuf, cstr,
    debug, info, libc, parse_mount_info, warn,
};
use libc::{c_uint, dev_t, major};
use nix::mount::MsFlags;
use nix::sys::stat::{Mode, SFlag, mknod};
use num_traits::AsPrimitive;
use std::cmp::Ordering::{Greater, Less};
use std::ffi::OsStr;
use std::path::{Path, PathBuf};

// Linux allocated devices: 240-254 are reserved for LOCAL/EXPERIMENTAL use.
const DYNAMIC_MAJOR_MIN: u32 = 240;
const DYNAMIC_MAJOR_MAX: u32 = 254;

pub fn setup_preinit_dir() {
    let magisk_tmp = get_magisk_tmp();

    // Mount preinit directory
    let dev_path = cstr::buf::new::<64>()
        .join_path(magisk_tmp)
        .join_path(PREINITDEV);
    if let Ok(attr) = dev_path.get_attr()
        && attr.st.st_mode & libc::S_IFMT as c_uint == libc::S_IFBLK.as_()
    {
        // DO NOT mount the block device directly, as we do not know the flags and configs
        // to properly mount the partition; mounting block devices directly as rw could cause
        // crashes if the filesystem driver is crap (e.g. some broken F2FS drivers).
        // What we do instead is to scan through the current mountinfo and find a pre-existing
        // mount point mounting our desired partition, and then bind mount the target folder.
        let preinit_dev = attr.st.st_rdev;
        let mnt_path = cstr::buf::default()
            .join_path(magisk_tmp)
            .join_path(PREINITMIRR);
        for info in parse_mount_info("self") {
            if info.root == "/" && info.device == preinit_dev {
                if !info.fs_option.split(',').any(|s| s == "rw") {
                    // Only care about rw mounts
                    continue;
                }
                let mut target = info.target;
                let target = Utf8CStr::from_string(&mut target);
                let mut preinit_dir = resolve_preinit_dir(target);
                let preinit_dir = Utf8CStr::from_string(&mut preinit_dir);
                let mut early_mnt_dir = resolve_early_mount_dir(target);
                let early_mnt_dir = Utf8CStr::from_string(&mut early_mnt_dir);
                let r = || -> LoggedResult<()> {
                    preinit_dir.mkdir(0o700)?;
                    mnt_path.mkdirs(0o755)?;
                    // A stale non-empty directory makes remove() and the
                    // symlink fail with EEXIST, leaving the preinit dir
                    // unreachable (upstream #10159). The path lives on the
                    // MAGISKTMP tmpfs, so the recursive removal can never
                    // touch the partition; symlinks and missing paths behave
                    // exactly as before.
                    mnt_path.remove_all().ok();
                    mnt_path.create_symlink_to(preinit_dir)?;
                    Ok(())
                }();
                if r.is_ok() {
                    info!("* Found preinit dir: {}", preinit_dir);
                    // Early mount dir: legacy K27 layout, a sibling of the
                    // preinit dir on the same partition (init reads it at
                    // boot, the installer writes module snippets there).
                    // Create it and expose the live partition copy in
                    // MAGISKTMP, stacking it over the tmpfs magiskinit
                    // mounted at the same path (K27's rec_mount).
                    let early_path = cstr::buf::default()
                        .join_path(magisk_tmp)
                        .join_path(EARLYMNT);
                    let _ = || -> LoggedResult<()> {
                        early_mnt_dir.mkdir(0o700)?;
                        cstr::buf::default()
                            .join_path(early_mnt_dir)
                            .join_path("initrc.d")
                            .mkdir(0o700)?;
                        early_path.mkdirs(0o755)?;
                        early_mnt_dir.bind_mount_to(&early_path, true)?;
                        Ok(())
                    }();
                    return;
                }
            }
        }
    }

    warn!("mount: preinit dir not found");
}

// Boot-complete rebuilds the persistent sepolicy rules file from the
// installed modules: recovery has no daemon to create the runtime link,
// and init reads this file on the next boot. That restores the per-boot
// regeneration of the rules the old installer provided.
fn append_module_rules(base: &Utf8CStr, rules: &mut String) {
    let Ok(entries) = std::fs::read_dir(base.as_str()) else {
        return;
    };
    for entry in entries.flatten() {
        let module = entry.path();
        if !module.is_dir() {
            continue;
        }
        if module.join("disable").exists()
            || module.join("remove").exists()
            || module.join("update").exists()
        {
            continue;
        }
        if let Ok(rule) = std::fs::read_to_string(module.join("sepolicy.rule")) {
            rules.push_str(&rule);
            rules.push('\n');
            info!("{}: refresh [sepolicy.rule]", module.file_name().unwrap_or_default().to_string_lossy());
        }
    }
}

// Refresh the per-module early mount trees copied into the preinit dir.
// The init stage merges <preinit>/<module>/early-mount into its early
// mount tmpfs, so the copies must match the current module state on every
// boot: refreshed for enabled modules, dropped otherwise.
fn sync_module_early_mount(preinit_dir: &Utf8CStr) {
    let Ok(entries) = std::fs::read_dir(MODULEROOT) else {
        return;
    };
    for entry in entries.flatten() {
        let module = entry.path();
        if !module.is_dir() {
            continue;
        }
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let enabled = !module.join("disable").exists()
            && !module.join("remove").exists()
            && !module.join("update").exists();
        let early_mount = module.join("early-mount");

        let dest = cstr::buf::default()
            .join_path(preinit_dir)
            .join_path(&name)
            .join_path("early-mount");
        // Start from scratch: a disabled or updated module must not keep
        // injecting files from the copy left by a previous boot.
        dest.remove_all().ok();
        if !enabled || !early_mount.is_dir() {
            continue;
        }
        cstr::buf::default()
            .join_path(preinit_dir)
            .join_path(&name)
            .mkdirs(0o700)
            .ok();
        let mut src = early_mount.to_string_lossy().into_owned();
        let src = Utf8CStr::from_string(&mut src);
        match src.copy_to(&dest) {
            Ok(()) => info!("{}: refresh [early-mount]", name),
            Err(_) => warn!("mount: failed to copy early-mount for {}", name),
        }
    }
}

pub fn recover_preinit_rules() {
    let preinit_dir = cstr::buf::default()
        .join_path(get_magisk_tmp())
        .join_path(PREINITMIRR);
    if !preinit_dir.exists() {
        return;
    }
    let mut rules = String::new();
    append_module_rules(cstr!(MODULEROOT), &mut rules);
    append_module_rules(cstr!(MODULEUPGRADE), &mut rules);
    if !rules.is_empty() {
        let rule_file = cstr::buf::default()
            .join_path(preinit_dir.as_str())
            .join_path("sepolicy.rule");
        match std::fs::write(rule_file.as_str(), rules) {
            Ok(()) => info!("* Recovered preinit sepolicy.rule from installed modules"),
            Err(e) => warn!("mount: failed to update preinit sepolicy.rule: {}", e),
        }
    }
    sync_module_early_mount(&preinit_dir);
}

pub fn setup_module_mount() {
    // Bind remount module root to clear nosuid
    let module_mnt = cstr::buf::default()
        .join_path(get_magisk_tmp())
        .join_path(MODULEMNT);
    let _ = || -> LoggedResult<()> {
        module_mnt.mkdir(0o755)?;
        cstr!(MODULEROOT).bind_mount_to(&module_mnt, false)?;
        module_mnt.remount_mount_point_flags(MsFlags::MS_RDONLY)?;
        Ok(())
    }();
}

pub fn clean_mounts() {
    let magisk_tmp = get_magisk_tmp();

    let mut buf = cstr::buf::default();

    let module_mnt = buf.append_path(magisk_tmp).append_path(MODULEMNT);
    module_mnt.unmount().log_ok();
    buf.clear();

    let worker_dir = buf.append_path(magisk_tmp).append_path(WORKERDIR);
    let _ = || -> LoggedResult<()> {
        worker_dir.set_mount_private(true)?;
        worker_dir.unmount()?;
        Ok(())
    }();
}

// when partitions have the same fs type, the order is:
// - data: it has sufficient space and can be safely written
// - cache: size is limited, but still can be safely written
// - klogdump: available on some Smartisan devices and can be safely written
// - metadata: size is limited, and it might cause unexpected behavior if written
// - persist: it's the last resort, as it's dangerous to write to it
#[derive(PartialEq, Eq, PartialOrd, Ord)]
enum PartId {
    Data,
    Cache,
    Klogdump,
    Metadata,
    Persist,
}

enum EncryptType {
    None,
    Block,
    File,
    Metadata,
}

pub fn find_preinit_device() -> String {
    let encrypt_type = if get_prop(cstr!("ro.crypto.state")) != "encrypted" {
        EncryptType::None
    } else if get_prop(cstr!("ro.crypto.type")) == "block" {
        EncryptType::Block
    } else if get_prop(cstr!("ro.crypto.metadata.enabled")) == "true" {
        EncryptType::Metadata
    } else {
        EncryptType::File
    };

    let mut matched_info = parse_mount_info("self")
        .into_iter()
        .filter_map(|info| {
            if info.root != "/" || !info.source.starts_with('/') || info.source.contains("/dm-") {
                return None;
            }
            match info.fs_type.as_str() {
                "ext4" | "f2fs" => (),
                _ => return None,
            }
            if !info.fs_option.split(',').any(|s| s == "rw") {
                return None;
            }
            let path = Path::new(&info.source).parent()?;
            if !path.ends_with("by-name") && !path.ends_with("block") {
                return None;
            }
            // use device major number to filter out device-mapper
            let maj = major(info.device as dev_t) as u32;
            if (DYNAMIC_MAJOR_MIN..=DYNAMIC_MAJOR_MAX).contains(&maj)
                && !info.source.contains("/vd")
                && !info.source.contains("/by-name/")
            {
                return None;
            }
            // take data iff it's not encrypted or file-based encrypted without metadata
            // other partitions are always taken
            match info.target.as_str() {
                "/persist" | "/mnt/vendor/persist" => Some((PartId::Persist, info)),
                "/metadata" => Some((PartId::Metadata, info)),
                "/klogdump" => Some((PartId::Klogdump, info)),
                "/cache" => Some((PartId::Cache, info)),
                "/data" => Some((PartId::Data, info))
                    .take_if(|_| matches!(encrypt_type, EncryptType::None | EncryptType::File)),
                _ => None,
            }
        })
        .collect::<Vec<_>>();

    if matched_info.is_empty() {
        return String::new();
    }

    let (_, preinit_info, _) = matched_info.select_nth_unstable_by(
        0,
        |(ap, MountInfo { fs_type: at, .. }), (bp, MountInfo { fs_type: bt, .. })| match (
            ap,
            bp,
            at.as_str() == "ext4",
            bt.as_str() == "ext4",
        ) {
            // metadata is not affected by f2fs kernel bug
            (PartId::Metadata, _, _, true) | (_, PartId::Metadata, true, _) => ap.cmp(bp),
            // otherwise, take ext4 f2fs because f2fs has a kernel bug that causes kernel panic
            (_, _, true, false) => Less,
            (_, _, false, true) => Greater,
            // if both has the same fs type, compare the mount point
            _ => ap.cmp(bp),
        },
    );
    let info = &preinit_info.1;
    let mut target = info.target.clone();
    let mut preinit_dir = resolve_preinit_dir(Utf8CStr::from_string(&mut target));
    if unsafe { libc::getuid() } == 0
        && let Ok(tmp) = std::env::var("MAGISKTMP")
        && !tmp.is_empty()
    {
        let mut buf = cstr::buf::default();
        let mirror_dir = buf.append_path(&tmp).append_path(PREINITMIRR);
        let preinit_dir = Utf8CStr::from_string(&mut preinit_dir);
        let _ = || -> LoggedResult<()> {
            preinit_dir.mkdirs(0o700)?;
            mirror_dir.mkdirs(0o755)?;
            mirror_dir.unmount().ok();
            // The same stale-directory repair as in setup_preinit_dir.
            mirror_dir.remove_all().ok();
            mirror_dir.create_symlink_to(preinit_dir)?;
            Ok(())
        }();
        if std::env::var_os("MAKEDEV").is_some() {
            buf.clear();
            let dev_path = buf.append_path(&tmp).append_path(PREINITDEV);
            mknod(
                dev_path.as_utf8_cstr(),
                SFlag::S_IFBLK,
                Mode::from_bits_truncate(0o600),
                info.device as dev_t,
            )
            .check_os_err("mknod", Some(dev_path), None)
            .log_ok();
        }
    }
    Path::new(&info.source)
        .file_name()
        .and_then(OsStr::to_str)
        .unwrap_or_default()
        .to_string()
}

pub fn revert_unmount(pid: i32) {
    if pid > 0 {
        if switch_mnt_ns(pid) != 0 {
            return;
        }
        debug!("denylist: handling PID=[{}]", pid);
    }

    let mut targets = Vec::new();

    // Unmount Magisk tmpfs and mounts from module files.
    // Under SuList the Magisk mounts of non-listed processes are reverted
    // through the HideMounts bit, so widen the stock predicate to every
    // mount the Magisk stack may leave behind. This is a strict superset of
    // the stock `source == "magisk" || root.starts_with("/adb/modules")`.
    // The EARLYMNTNAME clause is K27's dedicated revert of early mount
    // files: the tmpfs and every bind mount from it share that source.
    for info in parse_mount_info("self") {
        let kitsu_match = info.source == "magisk"
            || info.source == EARLYMNTNAME
            || info.root.starts_with("/adb")
            || info.root.starts_with("/data/adb")
            || info.target.starts_with("/data/adb")
            || info.target.starts_with("/debug_ramdisk")
            || info.target.starts_with("/product/bin")
            || ((info.target == "/system/lib" || info.target == "/system/lib64")
                && info.fs_type == "tmpfs");
        if kitsu_match {
            targets.push(info.target);
        }
    }

    if targets.is_empty() {
        return;
    }

    let mut prev: Option<PathBuf> = None;
    targets.sort();
    targets.retain(|target| {
        if let Some(prev) = &prev
            && Path::new(target).starts_with(prev)
        {
            return false;
        }
        prev = Some(PathBuf::from(target.clone()));
        true
    });

    for mut target in targets {
        let target = Utf8CStr::from_string(&mut target);
        if target.unmount().is_ok() {
            debug!("denylist: Unmounted ({})", target);
        }
    }
}
