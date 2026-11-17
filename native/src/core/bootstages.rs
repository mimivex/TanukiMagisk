use crate::consts::{APP_DATA_DIR, APP_PACKAGE_NAME, BBPATH, DATABIN, MODULEROOT, SECURE_DIR};
use crate::daemon::MagiskD;
use crate::db::MultiuserMode;
use crate::ffi::{
    DbEntryKey, RequestCode, check_key_combo, exec_common_scripts, exec_module_scripts,
    get_magisk_tmp, initialize_denylist, scan_deny_apps,
};
use crate::logging::setup_logfile;
use crate::module::disable_modules;
use crate::mount::{clean_mounts, recover_preinit_rules, setup_preinit_dir};
use crate::resetprop::get_prop;
use crate::selinux::restorecon;
use base::const_format::concatcp;
use base::{BufReadExt, FsPathBuilder, ResultExt, Utf8CStrBuf, cstr, error, info};
use bitflags::bitflags;
use nix::fcntl::OFlag;
use std::io::BufReader;
use std::os::unix::net::UnixStream;
use std::process::{Command, Stdio};
use std::sync::atomic::Ordering;

bitflags! {
    #[derive(Default)]
    pub struct BootState : u32 {
        const PostFsDataDone = 1 << 0;
        const LateStartDone = 1 << 1;
        const BootComplete = 1 << 2;
        const SafeMode = 1 << 3;
    }
}

impl MagiskD {
    fn setup_magisk_env(&self) -> bool {
        info!("* Initializing Magisk environment");

        let mut buf = cstr::buf::default();

        for user in self.get_users() {
            buf.clear();
            buf.append_path(APP_DATA_DIR)
                .append_path_fmt(user)
                .append_path(APP_PACKAGE_NAME)
                .append_path("install");
            if buf.exists() {
                break;
            }
        }

        // Alternative binaries paths
        let alt_bin_dirs = [
            cstr!("/cache/data_adb/magisk"),
            cstr!("/data/magisk"),
            &buf,
        ];
        for dir in alt_bin_dirs {
            if dir.exists() {
                cstr!(DATABIN).remove_all().ok();
                dir.copy_to(cstr!(DATABIN)).ok();
                dir.remove_all().ok();
            }
        }
        cstr!("/cache/data_adb").remove_all().ok();

        // Directories in /data/adb
        cstr!(SECURE_DIR).follow_link().chmod(0o700).log_ok();
        cstr!(DATABIN).mkdir(0o755).log_ok();
        cstr!(MODULEROOT).mkdir(0o755).log_ok();
        cstr!(concatcp!(SECURE_DIR, "/post-fs-data.d"))
            .mkdir(0o755)
            .log_ok();
        cstr!(concatcp!(SECURE_DIR, "/service.d"))
            .mkdir(0o755)
            .log_ok();
        restorecon();

        let busybox = cstr!(concatcp!(DATABIN, "/busybox"));
        if !busybox.exists() {
            return false;
        }

        let tmp_bb = buf.append_path(get_magisk_tmp()).append_path(BBPATH);
        tmp_bb.mkdirs(0o755).ok();
        tmp_bb.append_path("busybox");
        busybox.copy_to(tmp_bb).ok();
        tmp_bb.follow_link().chmod(0o755).log_ok();

        // Install busybox applets
        Command::new(&tmp_bb)
            .arg("--install")
            .arg("-s")
            .arg(tmp_bb.parent_dir().unwrap_or_default())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .log_ok();

        // magisk32 and magiskpolicy are not installed into ramdisk and has to be copied
        // from data to magisk tmp
        let magisk32 = cstr!(concatcp!(DATABIN, "/magisk32"));
        if magisk32.exists() {
            let tmp = buf.append_path(get_magisk_tmp()).append_path("magisk32");
            magisk32.copy_to(tmp).log_ok();
        }
        let magiskpolicy = cstr!(concatcp!(DATABIN, "/magiskpolicy"));
        if magiskpolicy.exists() {
            let tmp = buf
                .append_path(get_magisk_tmp())
                .append_path("magiskpolicy");
            magiskpolicy.copy_to(tmp).log_ok();
        }

        true
    }

    fn post_fs_data(&self) -> bool {
        setup_logfile();
        info!("** post-fs-data mode running");

        // Check secure dir
        if !cstr!(SECURE_DIR).exists() {
            error!("* {} is not present, abort", SECURE_DIR);
            return true;
        }

        self.prune_su_access();

        if self.is_headless {
            self.set_db_setting(DbEntryKey::SuMultiuserMode, MultiuserMode::User as i32)
                .log_ok();
        }

        if !self.setup_magisk_env() {
            error!("* Magisk environment incomplete, abort");
            return true;
        }

        // Check safe mode
        let boot_cnt = self.get_db_setting(DbEntryKey::BootloopCount);
        self.set_db_setting(DbEntryKey::BootloopCount, boot_cnt + 1)
            .log()
            .ok();
        let safe_mode = boot_cnt >= 2
            || get_prop(cstr!("persist.sys.safemode")) == "1"
            || get_prop(cstr!("ro.sys.safemode")) == "1"
            || check_key_combo();

        if safe_mode {
            info!("* Safe mode triggered");
            // Disable MagiskHide/SuList via the stock key; `magiskhide` mirrors it.
            self.set_db_setting(DbEntryKey::DenylistConfig, 0).log().ok();
            self.set_db_setting(DbEntryKey::MagiskHideConfig, 0)
                .log()
                .ok();
            // Disable all modules and zygisk so next boot will be clean
            disable_modules();
            self.set_db_setting(DbEntryKey::ZygiskConfig, 0).log_ok();
            return true;
        }

        exec_common_scripts(cstr!("post-fs-data"));
        self.zygisk_enabled.store(
            self.get_db_setting(DbEntryKey::ZygiskConfig) != 0,
            Ordering::Release,
        );
        initialize_denylist();
        self.handle_modules();
        clean_mounts();

        false
    }

    fn late_start(&self) {
        setup_logfile();
        info!("** late_start service mode running");

        exec_common_scripts(cstr!("service"));
        if let Some(module_list) = self.module_list.get() {
            exec_module_scripts(cstr!("service"), module_list);
        }
    }

    fn boot_complete(&self) {
        setup_logfile();
        info!("** boot-complete triggered");

        // Module boot-completed scripts: modules only, after the system is
        // fully up, as in Kitsune 27.
        if let Some(module_list) = self.module_list.get() {
            exec_module_scripts(cstr!("boot-completed"), module_list);
        }

        // Reset the bootloop counter once we have boot-complete
        self.set_db_setting(DbEntryKey::BootloopCount, 0).log_ok();

        // User 0 is decrypted now: refresh the deny engine's app-id map.
        scan_deny_apps();

        // At this point it's safe to create the folder
        let secure_dir = cstr!(SECURE_DIR);
        if !secure_dir.exists() {
            secure_dir.mkdir(0o700).log_ok();
        }

        if self.is_headless {
            self.set_db_setting(DbEntryKey::SuMultiuserMode, MultiuserMode::User as i32)
                .log_ok();
        }

        setup_preinit_dir();
        // The installer cannot write here without a daemon
        recover_preinit_rules();
        if self.zygisk_enabled.load(Ordering::Relaxed) {
            self.zygisk.lock().reset(true);
        }
        // Install the full manager bundled by the zip installer (recovery
        // flash has no patched ramdisk stub). Retries every boot until the
        // install script records a successful install imprint.
        self.ensure_bundled_manager();
    }

    pub fn boot_stage_handler(&self, client: UnixStream, code: RequestCode) {
        // Make sure boot stage execution is always serialized
        let mut state = self.boot_stage_lock.lock();

        match code {
            RequestCode::POST_FS_DATA
                if check_data() && !state.contains(BootState::PostFsDataDone) =>
            {
                if self.post_fs_data() {
                    state.insert(BootState::SafeMode);
                }
                state.insert(BootState::PostFsDataDone);
            }
            RequestCode::LATE_START => {
                drop(client);
                if state.contains(BootState::PostFsDataDone) && !state.contains(BootState::SafeMode)
                {
                    self.late_start();
                    state.insert(BootState::LateStartDone);
                }
            }
            RequestCode::BOOT_COMPLETE => {
                drop(client);
                // Do not gate the manager bootstrap on post-fs-data: a
                // recovery install has no ramdisk stub and must still get the
                // full manager. The bootloop counter is reset here as usual.
                if !state.contains(BootState::BootComplete) {
                    state.insert(BootState::BootComplete);
                    self.boot_complete()
                }
            }
            _ => {}
        }
    }
}

fn check_data() -> bool {
    if let Ok(file) = cstr!("/proc/mounts").open(OFlag::O_RDONLY | OFlag::O_CLOEXEC) {
        let mut mnt = false;
        BufReader::new(file).for_each_line(|line| {
            if line.contains(" /data ") && !line.contains("tmpfs") {
                mnt = true;
                return false;
            }
            true
        });
        if !mnt {
            return false;
        }
        let crypto = get_prop(cstr!("ro.crypto.state"));
        return if !crypto.is_empty() {
            if crypto != "encrypted" {
                // Unencrypted, we can directly access data
                true
            } else {
                // Encrypted, check whether vold is started
                !get_prop(cstr!("init.svc.vold")).is_empty()
            }
        } else {
            // ro.crypto.state is not set, assume it's unencrypted
            true
        };
    }
    false
}
