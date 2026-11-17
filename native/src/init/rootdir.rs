use crate::consts::{MAGISK_FILE_CON, ROOTMNT, ROOTOVL};
use crate::ffi::MagiskInit;
use base::nix::fcntl::OFlag;
use base::{
    BufReadExt, Directory, FsPathBuilder, LoggedResult, ResultExt, Utf8CStr, Utf8CString,
    clone_attr, cstr, debug, info, warn,
};
use std::ffi::OsStr;
use std::fs::File;
use std::io::{BufReader, Read, Write};
use std::mem;
use std::os::fd::{FromRawFd, RawFd};
use std::path::Path;

pub fn inject_magisk_rc(fd: RawFd, tmp_dir: &Utf8CStr) {
    debug!("Injecting magisk rc");

    let mut file = unsafe { File::from_raw_fd(fd) };

    write!(
        file,
        r#"
on post-fs-data
    exec {0} 0 0 -- {1}/magisk --post-fs-data

on property:vold.decrypt=trigger_restart_framework
    exec {0} 0 0 -- {1}/magisk --service

on nonencrypted
    exec {0} 0 0 -- {1}/magisk --service

on property:sys.boot_completed=1
    exec {0} 0 0 -- {1}/magisk --boot-complete
"#,
        "u:r:magisk:s0", tmp_dir
    )
    .ok();

    mem::forget(file)
}

pub struct OverlayAttr(Utf8CString, Utf8CString);

impl MagiskInit {
    pub(crate) fn parse_config_file(&mut self) {
        if let Ok(fd) = cstr!("/data/.backup/.magisk").open(OFlag::O_RDONLY) {
            let mut reader = BufReader::new(fd);
            reader.for_each_prop(|key, val| {
                if key == "PREINITDEVICE" {
                    self.preinit_dev = val.to_string();
                    return false;
                }
                true
            })
        }
    }

    fn mount_impl(
        &mut self,
        src_dir: &Utf8CStr,
        dest_dir: &Utf8CStr,
        mount_list: &mut String,
    ) -> LoggedResult<()> {
        let mut dir = Directory::open(src_dir)?;
        let mut con = cstr::buf::default();
        loop {
            match &dir.read()? {
                None => return Ok(()),
                Some(e) => {
                    let name = e.name();
                    let src = cstr::buf::dynamic(256).join_path(src_dir).join_path(name);
                    let dest = cstr::buf::dynamic(256).join_path(dest_dir).join_path(name);
                    if dest.exists() {
                        if e.is_dir() {
                            // Recursive
                            self.mount_impl(&src, &dest, mount_list)?;
                        } else {
                            debug!("Mount [{}] -> [{}]", src, dest);
                            clone_attr(&dest, &src)?;
                            dest.get_secontext(&mut con)?;
                            src.bind_mount_to(&dest, false)?;
                            self.overlay_con
                                .push(OverlayAttr(dest.to_owned(), con.to_owned()));
                            mount_list.push_str(dest.as_str());
                            mount_list.push('\n');
                        }
                    }
                }
            }
        }
    }

    pub(crate) fn mount_overlay(&mut self, dest: &Utf8CStr) {
        let mut mount_list = String::new();
        self.mount_impl(cstr!(ROOTOVL), dest, &mut mount_list)
            .log_ok();
        if let Ok(mut fd) = cstr!(ROOTMNT).create(OFlag::O_CREAT | OFlag::O_WRONLY, 0) {
            fd.write(mount_list.as_bytes()).log_ok();
        }
    }

    pub(crate) fn restore_overlay_contexts(&self) {
        self.overlay_con.iter().for_each(|attr| {
            let OverlayAttr(path, con) = attr;
            path.set_secontext(con).log_ok();
        })
    }
}

// HyperOS (hyos_spawner) support.
//
// HyperOS starts applications from /system_ext/bin/hyos_spawner instead of zygote,
// and that process never loads the ART native bridge, so the built-in Zygisk is
// never initialized there. When the spawner is detected, magiskinit stages the
// shim (hyos_shim.c) and the Magisk binary in /dev labeled magisk_file (a type
// every domain is allowed to access), records the SELinux rules the shim needs to
// read its opt-in marker, and mirrors the spawner's service definition into the
// rootfs overlay with a per-service `setenv LD_PRELOAD`.
//
// The opt-in (/data/adb/hyos_shim_enable) is enforced by the shim, not here: this
// runs at second-stage init, before /data is mounted, so the marker can only be
// read from the spawner itself, once /data is available. Without the marker the
// shim removes LD_PRELOAD and exits, which keeps the preload harmless on every
// device and boot.
const HYOS_SPAWNER: &Utf8CStr = cstr!("/system_ext/bin/hyos_spawner");
const HYOS_SHIM: &Utf8CStr = cstr!("/dev/hyos_shim.so");
const HYOS_MAGISK: &Utf8CStr = cstr!("/dev/hyos_magisk");
const HYOS_SECLABEL: &Utf8CStr = cstr!("/dev/hyos_seclabel");
const HYOS_RULES: &Utf8CStr = cstr!("/dev/hyos_sepolicy.rules");
const HYOS_RC_DIRS: &[&str] = &[
    "/system/etc/init/hw",
    "/system/etc/init",
    "/system_ext/etc/init/hw",
    "/system_ext/etc/init",
    "/vendor/etc/init/hw",
    "/vendor/etc/init",
    "/odm/etc/init/hw",
    "/odm/etc/init",
    "/product/etc/init/hw",
    "/product/etc/init",
];

fn elf_class(path: &str) -> Option<u8> {
    let mut buf = [0u8; 5];
    File::open(path).ok()?.read_exact(&mut buf).ok()?;
    (buf[..4] == *b"\x7fELF").then_some(buf[4])
}

fn stage_hyos_file(src: &str, dest: &Utf8CStr) -> bool {
    if std::fs::copy(src, dest.as_str()).is_err() {
        warn!("HyOS: cannot copy [{}] to [{}]", src, dest);
        return false;
    }
    // Label before the policy is loaded: magisk_file is accessible to any domain,
    // whereas the HyOS spawner cannot be expected to reach the paths we use.
    if let Err(e) = dest.set_secontext(cstr!(MAGISK_FILE_CON)) {
        warn!("HyOS: cannot label [{}]: {}", dest, e);
        return false;
    }
    true
}

fn is_rc_section(line: &str) -> bool {
    let trimmed = line.trim_start();
    !line.starts_with(' ') && !line.starts_with('\t') && !trimmed.is_empty() && !trimmed.starts_with('#')
}

// Returns the mirrored content for an rc file that defines the HyOS spawner service,
// together with the SELinux context init will run that service in.
fn patched_hyos_rc(path: &str) -> Option<(Vec<u8>, String)> {
    let data = std::fs::read(path).ok()?;
    let text = std::str::from_utf8(&data).ok()?;
    let lines: Vec<&str> = text.split_inclusive('\n').collect();

    let mut inject_after = Vec::new();
    let mut seclabel = None;
    for (i, line) in lines.iter().enumerate() {
        if !is_rc_section(line) {
            continue;
        }
        let trimmed = line.trim_start();
        let mut fields = trimmed.split_whitespace();
        if fields.next() != Some("service") || !trimmed.contains(HYOS_SPAWNER.as_str()) {
            continue;
        }
        let name = fields.next().unwrap_or("hyos_spawner");

        let block_end = lines
            .iter()
            .skip(i + 1)
            .position(|l| is_rc_section(l))
            .map(|n| n + i + 1)
            .unwrap_or(lines.len());
        let block = &lines[i + 1..block_end];

        // Never override a preload the platform already sets for the spawner.
        if block
            .iter()
            .any(|l| l.trim_start().starts_with("setenv LD_PRELOAD"))
        {
            debug!("HyOS: [{}] already sets LD_PRELOAD for hyos_spawner, not overriding it", path);
            return None;
        }

        if seclabel.is_none() {
            seclabel = Some(
                block
                    .iter()
                    .find_map(|l| l.trim_start().strip_prefix("seclabel "))
                    .map(|con| con.trim().to_string())
                    .filter(|con| !con.is_empty())
                    // init derives the context from the service name when no seclabel is set
                    .unwrap_or_else(|| format!("u:r:{name}:s0")),
            );
        }
        inject_after.push(i);
    }
    if inject_after.is_empty() {
        return None;
    }

    let mut out = String::with_capacity(data.len() + 64 * inject_after.len());
    for (i, line) in lines.iter().enumerate() {
        out.push_str(line);
        if inject_after.contains(&i) {
            if !line.ends_with('\n') {
                out.push('\n');
            }
            out.push_str("    setenv LD_PRELOAD ");
            out.push_str(HYOS_SHIM.as_str());
            out.push('\n');
        }
    }
    Some((out.into_bytes(), seclabel.unwrap()))
}

impl MagiskInit {
    pub(crate) fn install_hyos_shim(&mut self) {
        if !HYOS_SPAWNER.exists() {
            return;
        }

        // The shim is preloaded from the ramdisk; a class mismatch would abort the
        // spawner, so verify both sides before touching anything.
        let Some(shim_class) = elf_class("hyos") else {
            warn!("HyOS: shim not found in the ramdisk, skipping");
            return;
        };
        let Some(spawner_class) = elf_class(HYOS_SPAWNER.as_str()) else {
            warn!("HyOS: cannot inspect hyos_spawner, skipping");
            return;
        };
        if shim_class != spawner_class {
            warn!(
                "HyOS: shim ELF class {} does not match hyos_spawner class {}, skipping",
                shim_class, spawner_class
            );
            return;
        }

        let candidates: Vec<(String, Vec<u8>, String)> = HYOS_RC_DIRS
            .iter()
            .filter_map(|dir| std::fs::read_dir(dir).ok())
            .flatten()
            .filter_map(|e| e.ok())
            .filter(|e| e.path().extension() == Some(OsStr::new("rc")))
            .filter_map(|e| {
                let path = e.path();
                let path = path.to_str()?;
                patched_hyos_rc(path).map(|(content, con)| (path.to_string(), content, con))
            })
            .collect();
        if candidates.is_empty() {
            warn!("HyOS: hyos_spawner service definition not found, skipping");
            return;
        }

        // The shim reads its opt-in marker from the mounted /data, so the spawner
        // domain needs access to /data/adb. Parse the domain out of the service
        // security context; anything unexpected aborts before the preload is armed.
        let seclabel = &candidates[0].2;
        let Some(domain) = seclabel.split(':').nth(2).filter(|domain| {
            !domain.is_empty() && domain.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        }) else {
            warn!("HyOS: unexpected spawner security context [{}], skipping", seclabel);
            return;
        };

        if !stage_hyos_file("hyos", HYOS_SHIM) || !stage_hyos_file("magisk", HYOS_MAGISK) {
            return;
        }

        // Written before the mirror arms the preload: without these rules the shim
        // cannot read the opt-in marker and stays a no-op.
        let rules = format!(
            "allow {domain} adb_data_file dir search\n\
             allow {domain} adb_data_file file {{ open read getattr }}\n"
        );
        if std::fs::write(HYOS_RULES.as_str(), rules.as_bytes()).is_err()
            || HYOS_RULES.set_secontext(cstr!(MAGISK_FILE_CON)).is_err()
        {
            warn!("HyOS: cannot record the shim SELinux rules, skipping");
            return;
        }

        for (path, content, _) in &candidates {
            let dest = format!("{}{}", ROOTOVL, path);
            let Some(parent) = Path::new(&dest).parent() else {
                continue;
            };
            if std::fs::create_dir_all(parent).is_err() || std::fs::write(&dest, content).is_err() {
                warn!("HyOS: cannot mirror [{}], skipping", path);
                return;
            }
            debug!("HyOS: preloading the Zygisk shim in [{}]", path);
        }

        // Spawner children ask the daemon for module FDs from whatever context init
        // runs the spawner in; record it so the daemon can let them through.
        if std::fs::write(HYOS_SECLABEL.as_str(), candidates[0].2.as_bytes()).is_err()
            || HYOS_SECLABEL
                .set_secontext(cstr!(MAGISK_FILE_CON))
                .is_err()
        {
            warn!("HyOS: cannot record the spawner security context");
            return;
        }
        debug!("HyOS: spawner security context [{}]", &candidates[0].2);
    }
}
