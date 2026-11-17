use super::SuInfo;
use super::db::RootSettings;
use crate::consts::{INTERNAL_DIR, MAGISK_FILE_CON};
use crate::daemon::{AID_APP_START, to_app_id, to_user_id};
use crate::ffi::{SuPolicy, SuRequest, fork_dont_care, get_magisk_tmp};
use crate::socket::IpcRead;
use ExtraVal::{Bool, Int, IntList, Str};
use base::{BytesExt, FileAttr, LibcReturn, LoggedResult, ResultExt, Utf8CStrBuf, cstr, error, libc};
use nix::fcntl::OFlag;
use nix::poll::{PollFd, PollFlags, PollTimeout};
use num_traits::AsPrimitive;
use std::fmt::Write;
use std::fs::File;
use std::os::fd::AsFd;
use std::os::unix::net::UCred;
use std::os::unix::process::CommandExt;
use std::process::{Command, exit};

fn app_process(program: &str) -> Command {
    let mut cmd = Command::new(program);
    // daemon thread blocks all signals, unblock it before starting the child
    unsafe {
        cmd.pre_exec(|| nix::sys::signal::SigSet::empty().thread_set_mask().map_err(Into::into));
    }
    cmd
}

/// The su session ends when the client sees EOF on the socket pipes, and
/// this process inherited them as fd 0/1/2: the content or am call below
/// pipes its own child stdio, but these descriptors would stay open for the
/// whole call and hold the session past its end. Point them at /dev/null so
/// the session closes as soon as the su flow does, as the dup2 in the
/// Kitsune 27 exec_command child did.
fn detach_stdio() {
    use std::os::fd::AsRawFd;
    let Ok(null) = File::options().read(true).write(true).open("/dev/null") else {
        return;
    };
    for target in [0, 1, 2] {
        // Best effort: stdio plumbing must never fail the notification.
        unsafe { libc::dup2(null.as_raw_fd(), target) };
    }
}

struct Extra<'a> {
    key: &'static str,
    value: ExtraVal<'a>,
}

enum ExtraVal<'a> {
    Int(i32),
    Bool(bool),
    Str(&'a str),
    IntList(&'a [u32]),
}

impl Extra<'_> {
    fn add_intent(&self, cmd: &mut Command) {
        match self.value {
            Int(i) => {
                cmd.args(["--ei", self.key, &i.to_string()]);
            }
            Bool(b) => {
                cmd.args(["--ez", self.key, &b.to_string()]);
            }
            Str(s) => {
                cmd.args(["--es", self.key, s]);
            }
            IntList(list) => {
                cmd.args(["--es", self.key]);
                let mut tmp = String::new();
                list.iter().for_each(|i| {
                    write!(&mut tmp, "{i},").ok();
                });
                tmp.pop();
                cmd.arg(&tmp);
            }
        }
    }

    fn add_bind(&self, cmd: &mut Command) {
        let mut tmp: String;
        match self.value {
            Int(i) => {
                tmp = format!("{}:i:{}", self.key, i);
            }
            Bool(b) => {
                tmp = format!("{}:b:{}", self.key, b);
            }
            Str(s) => {
                let s = s.replace("\\", "\\\\").replace(":", "\\:");
                tmp = format!("{}:s:{}", self.key, s);
            }
            IntList(list) => {
                tmp = format!("{}:s:", self.key);
                if !list.is_empty() {
                    list.iter().for_each(|i| {
                        write!(&mut tmp, "{i},").ok();
                    });
                    tmp.pop();
                }
            }
        }
        cmd.args(["--extra", &tmp]);
    }

    fn add_bind_legacy(&self, cmd: &mut Command) {
        match self.value {
            Str(s) => {
                let tmp = format!("{}:s:{}", self.key, s);
                cmd.args(["--extra", &tmp]);
            }
            _ => self.add_bind(cmd),
        }
    }
}

pub(super) struct SuAppContext<'a> {
    pub(super) cred: UCred,
    pub(super) request: &'a SuRequest,
    pub(super) info: &'a SuInfo,
    pub(super) settings: &'a mut RootSettings,
    pub(super) sdk_int: i32,
}

impl SuAppContext<'_> {
    fn exec_cmd(&self, user: &str, action: &'static str, extras: &[Extra]) {
        // The provider lives in the (possibly repackaged) manager, not in the
        // stock package name.
        let provider = format!("content://{}.provider", self.info.mgr_pkg);
        // A frozen (cached-app freezer) or killed manager never answers the
        // synchronous call below, leaking one ~150MB orphaned app_process per
        // su session: bound the wait to 20s (SIGTERM, then SIGKILL 5s later)
        // and drop the log if it never arrives.
        let mut cmd = app_process("/system/bin/timeout");
        cmd.args([
            "-k",
            "5",
            "20",
            "/system/bin/app_process",
            "/system/bin",
            "com.android.commands.content.Content",
            "call",
            "--uri",
            &provider,
            "--user",
            user,
            "--method",
            action,
        ]);
        if self.sdk_int >= 30 {
            extras.iter().for_each(|e| e.add_bind(&mut cmd))
        } else {
            extras.iter().for_each(|e| e.add_bind_legacy(&mut cmd))
        }
        cmd.env("CLASSPATH", "/system/framework/content.jar");

        match cmd.output() {
            Ok(output) => {
                if output.stderr.contains(b"Error") || output.stdout.contains(b"Error") {
                    // First line only: a failing `content` child dumps a full
                    // Java stack, and the deny is already recorded by the daemon.
                    let first = output.stderr.split(|&b| b == b'\n').next().unwrap_or_default();
                    error!("content call failed: {}", String::from_utf8_lossy(first));
                    // The provider call is not always available: HyperOS
                    // refuses it from the root daemon even with the manager
                    // running. Start the activity instead, as Kitsune 27 did;
                    // it carries the same extras and the app side handles it.
                    self.exec_am(user, action, extras);
                }
            }
            Err(e) => {
                error!("failed to execute content call: {e}");
            }
        }
    }

    fn exec_am(&self, user: &str, action: &'static str, extras: &[Extra]) {
        let mut cmd = app_process("/system/bin/app_process");
        cmd.args([
            "/system/bin",
            "com.android.commands.am.Am",
            "start",
            "-p",
            &self.info.mgr_pkg,
            "--user",
            user,
            "-a",
            "android.intent.action.VIEW",
            "-f",
            // FLAG_ACTIVITY_NEW_TASK|FLAG_ACTIVITY_MULTIPLE_TASK|
            // FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS|FLAG_INCLUDE_STOPPED_PACKAGES
            "0x18800020",
            "--es",
            "action",
            action,
        ]);
        extras.iter().for_each(|e| e.add_intent(&mut cmd));
        cmd.env("CLASSPATH", "/system/framework/am.jar");

        // Async start activity
        if fork_dont_care() == 0 {
            detach_stdio();
            let err = cmd.exec();
            error!("exec app_process: {err}");
            exit(1);
        }
    }

    fn app_request(&mut self) {
        let mut fifo = cstr::buf::new::<64>();
        fifo.write_fmt(format_args!(
            "{}/{}/su_request_{}",
            get_magisk_tmp(),
            INTERNAL_DIR,
            self.cred.pid.unwrap_or(-1)
        ))
        .ok();

        let user_id;
        let user = if to_app_id(self.info.eval_uid) < AID_APP_START {
            "current"
        } else {
            user_id = to_user_id(self.info.eval_uid).to_string();
            &user_id
        };

        let fd = || -> LoggedResult<Option<File>> {
            let mut attr = FileAttr::new();
            attr.st.st_mode = 0o600;
            attr.st.st_uid = self.info.mgr_uid.as_();
            attr.st.st_gid = self.info.mgr_uid.as_();
            attr.con.push_str(MAGISK_FILE_CON);

            fifo.mkfifo(0o600)?;
            fifo.set_attr(&attr)?;

            let extras = [
                Extra {
                    key: "fifo",
                    value: Str(&fifo),
                },
                Extra {
                    key: "uid",
                    value: Int(self.info.eval_uid),
                },
                Extra {
                    key: "pid",
                    value: Int(self.cred.pid.unwrap_or(-1)),
                },
            ];
            self.exec_am(user, "request", &extras);

            // Open with O_RDWR to prevent FIFO open block
            let fd = fifo.open(OFlag::O_RDWR | OFlag::O_CLOEXEC)?;
            let mut pfd = [PollFd::new(fd.as_fd(), PollFlags::POLLIN)];

            // Wait for data input for at most 70 seconds. A timeout is not an
            // error: the read below would block forever because the daemon
            // itself keeps the FIFO open in read-write mode.
            let ready = nix::poll::poll(
                &mut pfd,
                PollTimeout::try_from(70 * 1000).unwrap_or(PollTimeout::NONE),
            )
            .into_os_result("poll", None, None)?;
            Ok((ready > 0).then_some(fd))
        }();

        fifo.remove().log_ok();

        if let Ok(Some(mut fd)) = fd {
            self.settings.policy = SuPolicy {
                repr: fd
                    .read_decodable::<i32>()
                    .log()
                    .map(i32::from_be)
                    .unwrap_or(SuPolicy::Deny.repr),
            };
        } else {
            self.settings.policy = SuPolicy::Deny;
        };
    }

    fn app_notify(&self) {
        let mut command = self.request.command.join(" ");
        let mut legacy_cmd = false;
        if self.request.command.len() >= 4
            && self.request.command[1] == "-c"
            && !self.request.command[2].contains(b" ")
        {
            command = format!("(Syntax Error) {}", command);
            legacy_cmd = true;
        }
        let extras = [
            Extra {
                key: "from.uid",
                value: Int(self.cred.uid.as_()),
            },
            Extra {
                key: "to.uid",
                value: Int(self.request.target_uid),
            },
            Extra {
                key: "pid",
                value: Int(self.cred.pid.unwrap_or(-1).as_()),
            },
            Extra {
                key: "policy",
                value: Int(self.settings.policy.repr),
            },
            Extra {
                key: "target",
                value: Int(self.request.target_pid),
            },
            Extra {
                key: "context",
                value: Str(&self.request.context),
            },
            Extra {
                key: "gids",
                value: IntList(&self.request.gids),
            },
            Extra {
                key: "command",
                value: Str(&command),
            },
            Extra {
                key: "legacy_cmd",
                value: Bool(legacy_cmd),
            },
            Extra {
                key: "notify",
                value: Bool(self.settings.notify),
            },
            Extra {
                key: "log",
                value: Bool(self.settings.log),
            },
        ];
        let user_id;
        let user = if to_app_id(self.info.eval_uid) < AID_APP_START {
            "current"
        } else {
            user_id = to_user_id(self.info.eval_uid).to_string();
            &user_id
        };
        self.exec_cmd(user, "notify", &extras);
    }

    pub(super) fn connect_app(&mut self) {
        // If policy is undetermined, show dialog for user consent
        if self.settings.policy == SuPolicy::Query {
            if self.info.mgr_uid < 0 || self.info.mgr_pkg.is_empty() {
                self.settings.policy = SuPolicy::Deny;
            } else {
                self.app_request();
            }
        }

        if !self.settings.log && !self.settings.notify {
            return;
        }

        if fork_dont_care() != 0 {
            return;
        }

        detach_stdio();

        // Notify su usage to application
        self.app_notify();

        exit(0);
    }
}
