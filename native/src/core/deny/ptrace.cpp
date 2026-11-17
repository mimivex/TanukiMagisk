/*
 * Kitsune Magisk deny engine (MagiskHide/SuList), ported from Kitsune
 * Magisk 27.2 by HuskyDG. GPL-3.0; see LICENSE and THIRD_PARTY_LICENSES.md.
 */
#include <signal.h>
#include <pthread.h>
#include <sys/wait.h>
#include <sys/mount.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/inotify.h>
#include <sys/time.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <poll.h>
#include <vector>
#include <string>
#include <set>
#include <map>
#include <cinttypes>

#include <core.hpp>
#include <consts.hpp>
#include <base.hpp>

#include "deny.hpp"

using namespace std;

static long xptrace(int request, pid_t pid, void *addr = nullptr, void *data = nullptr) {
    long ret = ptrace(request, pid, addr, data);
    if (ret < 0)
        PLOGE("ptrace %d", pid);
    return ret;
}

static inline long xptrace(int request, pid_t pid, void *addr, uintptr_t data) {
    return xptrace(request, pid, addr, reinterpret_cast<void *>(data));
}

#define WEVENT(s) (((s) & 0xffff0000) >> 16)

// Process monitoring
std::atomic<unsigned long> monitor_thread{0};
static int inotify_fd = -1;
static int data_system_wd = -1;

// Set by the async-signal-safe handlers below; serviced by the monitor loop
// in normal thread context (see service_deferred_work). Handlers must never
// allocate, take locks or do any other non-async-signal-safe work: a signal
// can interrupt the monitor thread mid-malloc, and rescan_apps takes
// data_lock, which would self-deadlock the whole hide engine.
static volatile sig_atomic_t inotify_pending;
static volatile sig_atomic_t alarm_pending;
static volatile sig_atomic_t terminate_pending;

static int parse_ppid(int pid);
static bool check_process(int pid, const char *process = nullptr,
                          const char *context = nullptr, const char *exe = nullptr);
static bool is_process(int pid, int uid = 0);
static void new_zygote(int pid);
static void new_spawner(int pid);

/******************
 * Data structures
 ******************/

// PIDs routinely grow past 32768 on modern kernels, so this cannot be
// a fixed-size bitmap
struct pid_set {
    struct reference {
        pid_set &owner;
        int pid;
        operator bool() const { return owner.set.count(pid) != 0; }
        reference &operator=(bool value) {
            if (value)
                owner.set.insert(pid);
            else
                owner.set.erase(pid);
            return *this;
        }
    };

    reference operator[](int pid) { return reference{*this, pid}; }
    bool operator[](int pid) const { return set.count(pid) != 0; }
    void reset() { set.clear(); }

private:
    std::set<int> set;
};

// zygote pid -> mnt ns
static map<int, struct stat> zygote_map;

// PIDs whose forks we follow beyond zygote: currently hyos_spawner
static set<int> spawner_map;

// HyperOS starts hyos_spawner after the zygotes, so the periodic scan keeps
// running (slower) until it is found or this deadline passes
static bool spawner_found = false;
static timeval spawner_deadline{};

static bool spawner_deadline_passed() {
    timeval now{};
    gettimeofday(&now, nullptr);
    return now.tv_sec > spawner_deadline.tv_sec ||
           (now.tv_sec == spawner_deadline.tv_sec && now.tv_usec >= spawner_deadline.tv_usec);
}

// Following app_zygote's forks too is off by default: it can start before
// ZygiskNext has injected, and following it would race with the injection.
#define ATTACH_APP_ZYGOTE 0

// attaches set
static pid_set attaches;

// other set
static pid_set allowed;
static pid_set checked;

/********
 * Utils
 ********/

// #define PTRACE_LOG(fmt, args...) LOGD("PID=[%d] " fmt, pid, ##args)
#define PTRACE_LOG(...)

static inline bool str_contains(string_view a, string_view b) { return a.find(b) != string_view::npos; }

static bool selinux_enabled() {
    return access("/sys/fs/selinux/enforce", F_OK) == 0;
}

static bool have_32_bit() {
    return access("/system/bin/app_process32", F_OK) == 0;
}

static void detach_pid(int pid, int signal = 0) {
    attaches[pid] = false;
    allowed[pid] = false;
    checked[pid] = false;
    spawner_map.erase(pid);
    ptrace(PTRACE_DETACH, pid, 0, signal);
    PTRACE_LOG("detach\n");
}

static inline int read_ns(const int pid, struct stat *st) {
    char path[32];
    sprintf(path, "/proc/%d/ns/mnt", pid);
    return stat(path, st);
}

static int parse_ppid(int pid) {
    char path[32];
    int ppid;
    sprintf(path, "/proc/%d/stat", pid);
    auto stat = open_file(path, "re");
    if (!stat)
        return -1;
    // PID COMM STATE PPID .....
    fscanf(stat.get(), "%*d %*s %*c %d", &ppid);

    return ppid;
}

static bool is_zygote_done() {
#ifdef __LP64__
    int zygote_count = have_32_bit() ? 2 : 1;
    if ((int) zygote_map.size() >= zygote_count)
        return true;
#else
    if (zygote_map.size() >= 1)
        return true;
#endif

    return false;
}

static inline bool read_file(const char *file, char *buf, int count) {
    FILE *fp = fopen(file, "re");
    if (!fp)
        return false;
    fread(buf, count - 1, 1, fp);
    buf[count - 1] = '\0';
    fclose(fp);
    return true;
}

static bool check_process(int pid, const char *process, const char *context, const char *exe) {
    char path[128];
    char buf[1024];
    ssize_t len;

    if (!process) goto check_context;
    sprintf(path, "/proc/%d/cmdline", pid);
    if (!read_file(path, buf, sizeof(buf)) ||
        strcmp(buf, process) != 0)
        return false;

    check_context:
    if (!context) goto check_exe;
    sprintf(path, "/proc/%d/attr/current", pid);
    if (!read_file(path, buf, sizeof(buf)) ||
        !str_contains(buf, context))
        return false;

    check_exe:
    if (!exe) goto final;
    sprintf(path, "/proc/%d/exe", pid);
    len = readlink(path, buf, sizeof(buf) - 1);
    if (len != -1) {
        buf[len] = '\0';
    }
    if (strcmp(buf, exe) != 0)
        return false;

    final:
    return true;
}

static bool is_zygote(int pid) {
    if (selinux_enabled()) {
        // SELinux enabled: check both name and context for security
        return check_process(pid, "zygote", "u:r:zygote:s0", nullptr)
            || check_process(pid, "zygote64", "u:r:zygote:s0", nullptr)
            || check_process(pid, "zygote32", "u:r:zygote:s0", nullptr);
    } else {
        // SELinux disabled (e.g., Waydroid): only check process name
        return check_process(pid, "zygote", nullptr, nullptr)
            || check_process(pid, "zygote64", nullptr, nullptr)
            || check_process(pid, "zygote32", nullptr, nullptr);
    }
}

static void check_zygote() {
    bool system_server_started = false;
    vector<int> zygote_list;
    vector<int> spawner_list;

    crawl_procfs([&zygote_list, &spawner_list, &system_server_started](int pid) -> bool {
        // Zygote process
        if (is_process(pid) && is_zygote(pid) && parse_ppid(pid) == 1) {
            zygote_list.push_back(pid);
            return true;
        }

        // system_server: pid == 1000 and zygote is ppid
        if (is_process(pid, 1000) && is_zygote(parse_ppid(pid))) {
            system_server_started = true;
            return true;
        }

        // HyperOS spawns application processes from hyos_spawner instead of
        // zygote; follow its forks so they take the same hide path.
        if (check_process(pid, "hyos_spawner")) {
            spawner_list.push_back(pid);
            return true;
        }

        // Others
        return true;
    });

    if (system_server_started) {
        // system_server, starting trace zygote
        for (int pid : zygote_list) {
            new_zygote(pid);
        }
    }

    for (int pid : spawner_list) {
        new_spawner(pid);
    }

    if (is_zygote_done()) {
        if (!spawner_found && !spawner_deadline_passed()) {
            // Keep looking for hyos_spawner at a slower pace
            timeval val{.tv_sec = 1, .tv_usec = 0};
            itimerval interval{.it_interval = val, .it_value = val};
            setitimer(ITIMER_REAL, &interval, nullptr);
            return;
        }
        // Stop periodic scanning
        timeval val{.tv_sec = 0, .tv_usec = 0};
        itimerval interval{.it_interval = val, .it_value = val};
        setitimer(ITIMER_REAL, &interval, nullptr);
    }
}

#define APP_PROC "/system/bin/app_process"

static void setup_inotify() {
    inotify_fd = inotify_init1(IN_CLOEXEC);
    if (inotify_fd < 0)
        return;

    // Setup inotify asynchronous I/O
    fcntl(inotify_fd, F_SETFL, O_ASYNC);
    struct f_owner_ex ex = {
        .type = F_OWNER_TID,
        .pid = (pid_t) syscall(__NR_gettid)
    };
    fcntl(inotify_fd, F_SETOWN_EX, &ex);

    // Monitor packages.xml
    data_system_wd = inotify_add_watch(inotify_fd, "/data/system", IN_CLOSE_WRITE);

    // Monitor app installation
    inotify_add_watch(inotify_fd, APP_DATA_DIR, IN_CREATE);
    DIR *dirfp = opendir(APP_DATA_DIR);
    if (dirfp) {
        char buf[4098];
        struct dirent *dp;
        while ((dp = readdir(dirfp)) != nullptr) {
            if (strcmp(dp->d_name, ".") == 0 || strcmp(dp->d_name, "..") == 0)
                continue;
            ssprintf(buf, sizeof(buf) - 1, "%s/%s", APP_DATA_DIR, dp->d_name);
            LOGD("proc_monitor: monitor userspace ID=[%s]\n", dp->d_name);
            inotify_add_watch(inotify_fd, buf, IN_ATTRIB);
        }
        closedir(dirfp);
    }

    // Monitor app_process
    if (access(APP_PROC "32", F_OK) == 0) {
        inotify_add_watch(inotify_fd, APP_PROC "32", IN_ACCESS);
        if (access(APP_PROC "64", F_OK) == 0)
            inotify_add_watch(inotify_fd, APP_PROC "64", IN_ACCESS);
    } else {
        inotify_add_watch(inotify_fd, APP_PROC, IN_ACCESS);
    }
}

static bool is_process(int pid, int uid) {
    char buf[128];
    char key[32];
    int tgid;
    struct stat st{};
    sprintf(buf, "/proc/%d", pid);
    if (stat(buf, &st) || st.st_uid != (uid_t) uid)
        return false;
    sprintf(buf, "/proc/%d/status", pid);
    auto fp = fopen(buf, "re");
    // PID is dead
    if (!fp)
        return false;
    while (fgets(buf, sizeof(buf), fp)) {
        sscanf(buf, "%s", key);
        if (key == "Tgid:"sv) {
            sscanf(buf, "%*s %d", &tgid);
            fclose(fp);
            return tgid == pid;
        }
    }
    fclose(fp);
    return false;
}

/************************
 * Async signal handlers
 ************************/

// Handlers run on the monitor thread only (every other daemon thread blocks
// all signals; this thread unblocks them while sleeping in waitpid). They
// still must do the absolute minimum: a signal can interrupt this same
// thread inside malloc/printf mid-operation, so any real work has to happen
// back in the monitor loop (service_deferred_work).
static void inotify_event(int) { inotify_pending = 1; }
static void alarm_event(int) { alarm_pending = 1; }
static void term_event(int) { terminate_pending = 1; }

// Drain all pending inotify events. Runs in monitor-thread context: taking
// data_lock (rescan_apps) and forking (check_zygote) is safe here.
static void process_inotify_events() {
    if (inotify_fd < 0)
        return;
    struct pollfd pfd = {
        .fd = inotify_fd,
        .events = POLLIN,
        .revents = 0
    };
    bool need_check = false;
    while (poll(&pfd, 1, 0) > 0) {
        char buf[512];
        ssize_t len = read(inotify_fd, buf, sizeof(buf));
        if (len <= 0)
            break;
        // A single read can carry several events; process them all.
        for (ssize_t off = 0; off + (ssize_t) sizeof(struct inotify_event) <= len;) {
            auto event = reinterpret_cast<struct inotify_event *>(buf + off);
            off += sizeof(struct inotify_event) + event->len;
            if ((event->mask & IN_CREATE) && event->len > 0) {
                std::string path = std::string(APP_DATA_DIR) + "/" + event->name;
                LOGD("proc_monitor: monitor userspace ID=[%s]\n", event->name);
                inotify_add_watch(inotify_fd, path.data(), IN_ATTRIB);
                continue;
            }
            if ((event->wd == data_system_wd && event->len > 0 &&
                 event->name == "packages.xml"sv) ||
                (event->mask & IN_ATTRIB))
                rescan_apps();
            need_check = true;
        }
    }
    if (need_check)
        check_zygote();
}

// Monitor cleanup, run from the monitor loop once terminate_pending is set.
static void cleanup_monitor() {
    LOGD("proc_monitor: cleaning up\n");
    zygote_map.clear();
    spawner_map.clear();
    attaches.reset();
    checked.reset();
    allowed.reset();
    if (inotify_fd >= 0)
        close(inotify_fd);
    inotify_fd = -1;
    monitor_thread = (unsigned long) -1;
    // Restore all signal handlers that was set
    sigset_t set;
    sigfillset(&set);
    pthread_sigmask(SIG_BLOCK, &set, nullptr);
    struct sigaction act{};
    act.sa_handler = SIG_DFL;
    sigaction(SIGTERMTHRD, &act, nullptr);
    sigaction(SIGIO, &act, nullptr);
    sigaction(SIGALRM, &act, nullptr);
    LOGD("proc_monitor: terminate\n");
}

// Service the work deferred by the signal handlers. Checked at both points
// of the loop where a delivery can land: right after the mask is unblocked
// (pending signals are delivered synchronously there) and right after
// waitpid returns. Returns true when the monitor has to terminate.
static bool service_deferred_work() {
    if (terminate_pending) {
        cleanup_monitor();
        return true;
    }
    // Drain unconditionally: SIGIO deliveries can coalesce, so never trust
    // the flag to account for every event.
    process_inotify_events();
    if (alarm_pending) {
        alarm_pending = 0;
        check_zygote();
    }
    return false;
}

static bool ino_equal(struct stat st, struct stat st2) {
    return st.st_dev == st2.st_dev &&
        st.st_ino == st2.st_ino;
}

static int check_pid(int pid) {
    char path[128];
    char cmdline[1024];
    int uid = -1;
    int ppid = -1;
    struct stat st;
    sprintf(path, "/proc/%d", pid);
    if (stat(path, &st)) {
        // Process died unexpectedly, ignore
        goto not_target;
    }
    uid = st.st_uid;
    if (uid == 0) {
        return 0;
    }

    // check cmdline
    ssprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    if (!read_file(path, cmdline, sizeof(cmdline)))
        // Process died unexpectedly, ignore
        goto not_target;

    // still zygote
    if (cmdline == "zygote"sv || cmdline == "zygote32"sv || cmdline == "zygote64"sv ||
        cmdline == "usap32"sv || cmdline == "usap64"sv || cmdline == "<pre-initialized>"sv)
        return 0;

    // Ensure ns is separated
    struct stat ppid_st;
    ppid = parse_ppid(pid);
    read_ns(pid, &st);
    read_ns(ppid, &ppid_st);
    if (ino_equal(st, ppid_st)) {
        LOGW("proc_monitor: skip [%s] PID=[%d] PPID=[%d] UID=[%d]\n", cmdline, pid, ppid, uid);
        goto not_target;
    }

    // SuList keeps the mounts of the listed processes and hides Magisk from
    // everyone else; MagiskHide does the opposite. Either way the target loses
    // the Magisk mounts through the same revert.
    {
        bool target = is_deny_target(uid, cmdline, 95);
        if (target == sulist_enabled.load()) {
            // Under SuList this is a listed app we keep; log it the way
            // Kitsune logs the apps it positively mounts.
            if (sulist_enabled.load())
                LOGI("proc_monitor: [%s] PID=[%d] UID=[%d]\n", cmdline, pid, uid);
            goto not_target;
        }
    }

    // Under SuList every non-listed app is stripped here, which floods the
    // log; keep it at debug level. MagiskHide still reports the denylisted
    // set at INFO.
    if (sulist_enabled.load())
        LOGD("proc_monitor: [%s] PID=[%d] UID=[%d]\n", cmdline, pid, uid);
    else
        LOGI("proc_monitor: [%s] PID=[%d] UID=[%d]\n", cmdline, pid, uid);
    detach_pid(pid);
    kill(pid, SIGSTOP);
    revert_daemon(pid);
    return 1;

not_target:
    detach_pid(pid);
    return 1;
}

// Follow the forks of a process that is already stopped by the tracer: used
// for hyos_spawner, whose children must land in the regular dispatch.
static void follow_forks(int pid) {
    if (spawner_map.count(pid))
        return;
    spawner_map.insert(pid);
    xptrace(PTRACE_SETOPTIONS, pid, nullptr,
            PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACEEXIT | PTRACE_O_TRACESYSGOOD);
}

// HyperOS starts application processes from hyos_spawner; attach it when it
// is already running. Best effort: the SELinux context varies across builds
// and a failure just restores the previous coverage.
static void new_spawner(int pid) {
    if (spawner_map.count(pid))
        return;
    if (xptrace(PTRACE_ATTACH, pid) == -1)
        return;
    LOGI("proc_monitor: hyos_spawner PID=[%d]\n", pid);
    spawner_found = true;
    waitpid(pid, nullptr, __WALL | __WNOTHREAD);
    follow_forks(pid);
    xptrace(PTRACE_CONT, pid);
}

static void new_zygote(int pid) {
    struct stat st, init_st;
    if (read_ns(pid, &st) || read_ns(1, &init_st) ||
        (init_st.st_ino == st.st_ino && init_st.st_dev == st.st_dev))
        return;

    auto it = zygote_map.find(pid);
    if (it != zygote_map.end()) {
        it->second = st;
        return;
    }

    LOGI("proc_monitor: zygote PID=[%d]\n", pid);

    // attach_zygote
    if (xptrace(PTRACE_ATTACH, pid) == -1)
        return;
    LOGI("proc_monitor: ptrace zygote PID=[%d]\n", pid);
    zygote_map[pid] = st;

    waitpid(pid, nullptr, __WALL | __WNOTHREAD);
    xptrace(PTRACE_SETOPTIONS, pid, nullptr,
            PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACEEXIT);

    xptrace(PTRACE_CONT, pid);
}

#define DETACH_AND_CONT { detach_pid(pid); continue; }

static std::string get_content(int pid, const char *file) {
    char buf[1024];
    sprintf(buf, "/proc/%d/%s", pid, file);
    FILE *fp = fopen(buf, "re");
    if (fp) {
        fgets(buf, sizeof(buf), fp);
        fclose(fp);
        return std::string(buf);
    }
    return std::string("");
}

void proc_monitor() {
    monitor_thread = (unsigned long) pthread_self();

    // The daemon blocks all signals before spawning threads; fatal signals
    // must be unblocked again or the crash handler installed by the deny
    // engine would never run. Do it before taking the mask backup so they
    // survive the restore below.
    sigset_t fatal_set;
    sigemptyset(&fatal_set);
    sigaddset(&fatal_set, SIGSEGV);
    sigaddset(&fatal_set, SIGABRT);
    sigaddset(&fatal_set, SIGBUS);
    pthread_sigmask(SIG_UNBLOCK, &fatal_set, nullptr);

    // Reset cached result
    zygote_map.clear();
    spawner_map.clear();
    spawner_found = false;
    attaches.reset();
    checked.reset();
    allowed.reset();

    // Backup original mask
    sigset_t orig_mask;
    pthread_sigmask(SIG_SETMASK, nullptr, &orig_mask);

    sigset_t unblock_set;
    sigemptyset(&unblock_set);
    sigaddset(&unblock_set, SIGTERMTHRD);
    sigaddset(&unblock_set, SIGIO);
    sigaddset(&unblock_set, SIGALRM);

    struct sigaction act{};
    sigfillset(&act.sa_mask);
    act.sa_handler = SIG_IGN;
    sigaction(SIGTERMTHRD, &act, nullptr);
    sigaction(SIGIO, &act, nullptr);
    sigaction(SIGALRM, &act, nullptr);

    // Temporary unblock to clear pending signals
    pthread_sigmask(SIG_UNBLOCK, &unblock_set, nullptr);
    pthread_sigmask(SIG_SETMASK, &orig_mask, nullptr);

    act.sa_handler = term_event;
    sigaction(SIGTERMTHRD, &act, nullptr);
    act.sa_handler = inotify_event;
    sigaction(SIGIO, &act, nullptr);
    act.sa_handler = alarm_event;
    sigaction(SIGALRM, &act, nullptr);

    setup_inotify();

    // Give hyos_spawner, which HyperOS starts after the zygotes, a window
    // in which to show up
    gettimeofday(&spawner_deadline, nullptr);
    spawner_deadline.tv_sec += 60;

    // First try find existing system server and zygote
    check_zygote();
    rescan_apps();
    if (!is_zygote_done()) {
        // Periodic scan every 250ms
        timeval val{
            .tv_sec = 0, .tv_usec = 250000
        };
        itimerval interval{
            .it_interval = val, .it_value = val
        };
        setitimer(ITIMER_REAL, &interval, nullptr);
    } else if (!spawner_found && !spawner_deadline_passed()) {
        // Keep scanning slowly for hyos_spawner
        timeval val{
            .tv_sec = 1, .tv_usec = 0
        };
        itimerval interval{
            .it_interval = val, .it_value = val
        };
        setitimer(ITIMER_REAL, &interval, nullptr);
    }

    for (int status;;) {
        pthread_sigmask(SIG_UNBLOCK, &unblock_set, nullptr);
        if (service_deferred_work())
            return;
        const int pid = waitpid(-1, &status, __WALL | __WNOTHREAD);
        if (service_deferred_work())
            return;
        if (pid < 0) {
            if (errno == ECHILD) {
                // Nothing to wait yet, sleep and wait till signal interruption
                LOGD("proc_monitor: nothing to monitor, wait for signal\n");
                struct timespec ts = {
                    .tv_sec = INT_MAX,
                    .tv_nsec = 0
                };
                nanosleep(&ts, nullptr);
            }
            continue;
        }

        // Keep the async handlers blocked while we touch shared state: they are
        // only allowed to run while we sleep in waitpid.
        pthread_sigmask(SIG_BLOCK, &unblock_set, nullptr);

        if (!WIFSTOPPED(status) /* Ignore if not ptrace-stop */)
            DETACH_AND_CONT;

        int event = WEVENT(status);
        int signal = WSTOPSIG(status);

        if (signal == SIGTRAP && (zygote_map.count(pid) || spawner_map.count(pid)) && event) {
            unsigned long msg;
            xptrace(PTRACE_GETEVENTMSG, pid, nullptr, &msg);
            switch (event) {
            case PTRACE_EVENT_FORK:
            case PTRACE_EVENT_VFORK:
                PTRACE_LOG("zygote/spawner forked: [%lu]\n", msg);
                attaches[msg] = true;
                break;
            case PTRACE_EVENT_EXIT:
                PTRACE_LOG("zygote/spawner exited with status: [%lu]\n", msg);
                zygote_map.erase(pid);
                spawner_map.erase(pid);
                DETACH_AND_CONT;
            default:
                zygote_map.erase(pid);
                spawner_map.erase(pid);
                DETACH_AND_CONT;
            }
            xptrace(PTRACE_CONT, pid);
        } else if (signal == (SIGTRAP | 0x80)) {
            do {
                struct stat st{};
                char path[128];
                if (checked[pid]) goto CHECK_PROC;
                sprintf(path, "/proc/%d", pid);
                stat(path, &st);
                PTRACE_LOG("UID=[%d]\n", st.st_uid);
                if (st.st_uid == 0)
                    continue;
                if ((st.st_uid % 100000) >= 90000) {
                    PTRACE_LOG("is isolated process\n");
                    goto CHECK_PROC;
                }

                // With SuList every process must reach check_pid: the list
                // decides per process, and unlisted ones are cleaned there.
                if (sulist_enabled)
                    goto CHECK_PROC;

                // check if UID is on list
                if (!is_uid_on_list(st.st_uid))
                    goto DETACH_PROC;

                CHECK_PROC:
                    checked[pid] = true;
                if (!allowed[pid] && (
                        // app zygote
                        str_contains(get_content(pid, "attr/current"), "u:r:app_zygote:s0") ||
                        // until pre-initialized
                        get_content(pid, "cmdline") == "<pre-initialized>"))
                    allowed[pid] = true;

                if (!allowed[pid])
                    continue;

                if (check_pid(pid))
                    goto skip;
                continue;

                DETACH_PROC:
                    detach_pid(pid);
                goto skip;
            } while (false);
            xptrace(PTRACE_SYSCALL, pid);
        } else if (signal == SIGSTOP) {
            // SIGSTOP is produced by ptrace
            if (spawner_map.count(pid)) {
                // A followed spawner: resume it as is, its options already
                // include TRACEFORK and must not be overwritten
                xptrace(PTRACE_CONT, pid);
                continue;
            }
            if (!attaches[pid]) {
                // Double check if this is actually a process
                attaches[pid] = is_process(pid);
            }
            if (attaches[pid]) {
                // This is a process, continue monitoring
                PTRACE_LOG("SIGSTOP from child\n");
                xptrace(PTRACE_SETOPTIONS, pid, nullptr,
                    PTRACE_O_TRACESYSGOOD);
                xptrace(PTRACE_SYSCALL, pid);
                // TODO : inject syscall
            } else {
                // This is a thread, do NOT monitor
                PTRACE_LOG("SIGSTOP from thread\n");
                DETACH_AND_CONT;
            }
        } else {
            // Not caused by us, resend signal
            xptrace((!zygote_map.count(pid) && attaches[pid]) ?
                    PTRACE_SYSCALL : PTRACE_CONT, pid, nullptr, signal);
            PTRACE_LOG("signal [%d]\n", signal);
        }

        skip:
            continue;
    }
}

int start_proc_monitor() {
    return new_daemon_thread([](void *) -> void * {
        proc_monitor();
        return nullptr;
    });
}
