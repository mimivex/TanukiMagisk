#include <sys/types.h>
#include <sys/stat.h>
#include <sys/inotify.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <signal.h>
#include <limits.h>
#include <cctype>
#include <cstring>
#include <set>
#include <map>
#include <atomic>

#include <consts.hpp>
#include <sqlite.hpp>
#include <core.hpp>

#include "deny.hpp"

using namespace std;

// The active table drives the engine:
// - MagiskHide: the canonical denylist, where listed processes lose the mounts
// - SuList: the sulist table, where only listed processes keep the mounts
// hidelist is never active anymore; it is only read by the one-time migration.

// sulist_enabled is the mode the engine is actually driving (set at boot).
std::atomic<bool> sulist_enabled = false;
static const char *table_name = "denylist";

// String entries (such as the repackaged manager package) live in the strings
// table, not in settings, which only stores integers.
static string get_string_setting(const char *key) {
    string value;
    db_exec("SELECT value FROM strings WHERE key=?", {key},
            [&value](StringSlice, const DbValues &values) { value = values.get_text(0); });
    return value;
}

// One raw-SQL `settings` key per recommended row removed on purpose, through
// the applet or a stock client: tombstone_<table>_<pkg>_<proc>. The per-arm
// seed skips a tombstoned row until "Add recommended" or a manual re-add
// clears it. Raw SQL keeps the fixed DbEntryKey bridge enum untouched.
static string tombstone_key(const char *table, const char *pkg, const char *proc) {
    return string("tombstone_") + table + "_" + pkg + "_" + proc;
}

static bool has_tombstone(const char *table, const char *pkg, const char *proc) {
    bool set = false;
    string key = tombstone_key(table, pkg, proc);
    db_exec("SELECT value FROM settings WHERE key=?", {key.data()},
            [&set](StringSlice, const DbValues &values) { set = values.get_int(0) != 0; });
    return set;
}

static void set_tombstone(const char *table, const char *pkg, const char *proc) {
    string key = tombstone_key(table, pkg, proc);
    db_exec("INSERT OR REPLACE INTO settings (key,value) VALUES(?,1)", {key.data()});
}

static void clear_tombstone(const char *table, const char *pkg, const char *proc) {
    string key = tombstone_key(table, pkg, proc);
    db_exec("DELETE FROM settings WHERE key=?", {key.data()});
}

static void clear_tombstones(const char *table) {
    string pattern = string("tombstone_") + table + "_%";
    db_exec("DELETE FROM settings WHERE key LIKE ?", {pattern.data()});
}

// For the following data structures:
// If package name == ISOLATED_MAGIC, or app ID == -1, it means isolated service
// If package name == WEBVIEW_ZYGOTE_MAGIC, or app ID == 1053, it means webview zygote

// Package name -> list of process names
static unique_ptr<map<string, set<string, StringCmp>, StringCmp>> pkg_to_procs_;
#define pkg_to_procs (*pkg_to_procs_)

// app ID -> list of pkg names (string_view points to a pkg_to_procs key)
static unique_ptr<map<int, set<string_view>>> app_id_to_pkgs_;
#define app_id_to_pkgs (*app_id_to_pkgs_)

// Locks the data structures above
static pthread_mutex_t data_lock = PTHREAD_MUTEX_INITIALIZER;

atomic<bool> denylist_enforced = false;

static bool add_hide_set(const char *pkg, const char *proc, bool kill_targets = true);
static void rescan_apps_unlocked();

static int get_app_id(const string &pkg) {
    if (pkg == ISOLATED_MAGIC)
        return -1;
    if (pkg == WEBVIEW_ZYGOTE_MAGIC)
        return WEBVIEW_ZYGOTE_UID;
    return MagiskD::Get().get_app_id(pkg);
}

static void update_app_id(int app_id, const string &pkg, bool remove) {
    if (app_id <= 0)
        return;
    if (remove) {
        if (auto it = app_id_to_pkgs.find(app_id); it != app_id_to_pkgs.end()) {
            it->second.erase(pkg);
            if (it->second.empty()) {
                app_id_to_pkgs.erase(it);
            }
        }
    } else {
        app_id_to_pkgs[app_id].emplace(pkg);
    }
}

// SuList defaults as in Kitsune 27.2, kept in memory only. The repackaged
// manager, when present, is read from the database.
static void add_sulist_defaults() {
    auto add = [](const string &pkg) {
        add_hide_set(pkg.data(), pkg.data(), false);
        update_app_id(get_app_id(pkg), pkg, false);
    };
    add("com.android.systemui");
    add("com.android.settings");
    add(JAVA_PACKAGE_NAME);
    auto manager_pkg = get_string_setting("requester");
    if (!manager_pkg.empty() && manager_pkg != JAVA_PACKAGE_NAME)
        add(manager_pkg);
}

// The recommended rows of each mode, keyed by the table they belong to. They
// are (package, process) pairs: com.google.android.gms.unstable is a process
// of GMS, not a package. db_add() rejects packages that are not installed, so
// missing entries are skipped instead of failing the whole add. The (gms, gms)
// pair is the main process, the row the deny screen renders and edits.
struct RecommendEntry {
    const char *pkg;
    const char *proc;
};

static constexpr RecommendEntry sulist_recommended[] = {
    { "com.android.systemui", "com.android.systemui" },
    { "com.android.settings", "com.android.settings" },
    { "io.github.mimivex.tanuki", "io.github.mimivex.tanuki" },
};

static constexpr RecommendEntry denylist_recommended[] = {
    { "com.google.android.gms", "com.google.android.gms" },
    { "com.google.android.gms", "com.google.android.gms.unstable" },
    { "com.google.android.gsf", "com.google.process.gservices" },
    { "com.google.android.gsf", "com.google.process.gapps" },
    { "com.google.android.gms", "com.android.gms" },
    { "com.android.vending", "com.android.vending" },
};

static int add_list(const char *pkg, const char *proc, bool use_active);
static int add_list_to(const char *table, const char *pkg, const char *proc);

struct RecommendList {
    const RecommendEntry *entries;
    size_t count;
};

static RecommendList recommend_list(const char *table) {
    if (strcmp(table, "sulist") == 0)
        return { sulist_recommended, sizeof(sulist_recommended) / sizeof(sulist_recommended[0]) };
    return { denylist_recommended, sizeof(denylist_recommended) / sizeof(denylist_recommended[0]) };
}

static bool is_recommended_pair(const char *table, const char *pkg, const char *proc) {
    auto list = recommend_list(table);
    for (size_t i = 0; i < list.count; ++i) {
        if (strcmp(list.entries[i].pkg, pkg) == 0 && strcmp(list.entries[i].proc, proc) == 0)
            return true;
    }
    return false;
}

static bool recommended_pkg(const char *table, const char *pkg) {
    auto list = recommend_list(table);
    for (size_t i = 0; i < list.count; ++i) {
        if (strcmp(list.entries[i].pkg, pkg) == 0)
            return true;
    }
    return false;
}

// The recommended rows live in the applied table only. Every arm re-adds
// the missing ones; a row removed on purpose carries a tombstone and stays
// away. "Add recommended" clears the tombstones of the table first, then
// re-adds everything.
void seed_recommended(bool full_request) {
    if (full_request)
        clear_tombstones(table_name);
    auto list = recommend_list(table_name);
    for (size_t i = 0; i < list.count; ++i) {
        const auto &e = list.entries[i];
        if (has_tombstone(table_name, e.pkg, e.proc))
            continue;
        add_list_to(table_name, e.pkg, e.proc);
    }
}

static void rescan_apps_unlocked() {
    LOGD("denylist: rescanning apps\n");

    app_id_to_pkgs.clear();

    auto data_dir = xopen_dir(APP_DATA_DIR);
    if (!data_dir)
        return;
    dirent *entry;
    while ((entry = xreaddir(data_dir.get()))) {
        // For each user
        int dfd = xopenat(dirfd(data_dir.get()), entry->d_name, O_RDONLY);
        if (auto dir = xopen_dir(dfd)) {
            while ((entry = xreaddir(dir.get()))) {
                struct stat st{};
                // For each package
                if (fstatat(dfd, entry->d_name, &st, 0))
                    continue;
                int app_id = to_app_id(st.st_uid);
                if (auto it = pkg_to_procs.find(entry->d_name); it != pkg_to_procs.end()) {
                    app_id_to_pkgs[app_id].insert(it->first);
                }
            }
        } else {
            close(dfd);
        }
    }
}

void rescan_apps() {
    mutex_guard lock(data_lock);
    if (pkg_to_procs_)
        rescan_apps_unlocked();
}

static set<string> get_users() {
    set<string> result{"0"};
    auto data_dir = xopen_dir(APP_DATA_DIR);
    if (!data_dir)
        return result;
    dirent *entry;
    while ((entry = xreaddir(data_dir.get()))) {
        result.insert(entry->d_name);
    }
    return result;
}

// Open /proc per call: the monitor thread and the pool threads both crawl it,
// and a shared DIR* would race on rewinddir/readdir.
void crawl_procfs(const std::function<bool(int)> &fn) {
    auto procfp = xopen_dir("/proc");
    if (!procfp)
        return;
    dirent *dp;
    int pid;
    while ((dp = xreaddir(procfp.get()))) {
        pid = parse_int(dp->d_name);
        if (pid > 0 && !fn(pid))
            break;
    }
}

static inline bool str_eql(string_view a, string_view b) { return a == b; }
static inline bool str_starts(string_view a, string_view b) { return a.starts_with(b); }

template <bool str_op(string_view, string_view) = &str_eql>
static bool proc_name_match(int pid, string_view name) {
    char buf[4019];
    sprintf(buf, "/proc/%d/cmdline", pid);
    if (auto fp = open_file(buf, "re")) {
        fgets(buf, sizeof(buf), fp.get());
        if (str_op(buf, name)) {
            return true;
        }
    }
    return false;
}

bool proc_context_match(int pid, string_view context) {
    char buf[PATH_MAX];
    char con[1024] = {0};

    sprintf(buf, "/proc/%d", pid);
    if (lgetfilecon(buf, byte_data{con, sizeof(con)})) {
        return string_view(con).starts_with(context);
    }
    return false;
}

template <bool matcher(int, string_view) = &proc_name_match>
static void kill_process(const char *name, bool multi = false) {
    crawl_procfs([=](int pid) -> bool {
        if (matcher(pid, name)) {
            kill(pid, SIGKILL);
            LOGD("denylist: kill PID=[%d] (%s)\n", pid, name);
            return multi;
        }
        return true;
    });
}

static bool validate(const char *pkg, const char *proc) {
    bool pkg_valid = false;
    bool proc_valid = true;

    if (str_eql(pkg, ISOLATED_MAGIC)) {
        pkg_valid = true;
        for (char c; (c = *proc); ++proc) {
            if (isalnum(c) || c == '_' || c == '.')
                continue;
            if (c == ':')
                break;
            proc_valid = false;
            break;
        }
    } else if (str_eql(pkg, WEBVIEW_ZYGOTE_MAGIC)) {
        pkg_valid = true;
        proc_valid = str_eql(proc, WEBVIEW_ZYGOTE_MAGIC);
    } else {
        for (char c; (c = *pkg); ++pkg) {
            if (isalnum(c) || c == '_')
                continue;
            if (c == '.') {
                pkg_valid = true;
                continue;
            }
            pkg_valid = false;
            break;
        }

        for (char c; (c = *proc); ++proc) {
            if (isalnum(c) || c == '_' || c == ':' || c == '.')
                continue;
            proc_valid = false;
            break;
        }
    }
    return pkg_valid && proc_valid;
}

static bool add_hide_set(const char *pkg, const char *proc, bool kill_targets) {
    auto p = pkg_to_procs[pkg].emplace(proc);
    if (!p.second)
        return false;
    LOGI("%s add: [%s/%s]\n", table_name, pkg, proc);
    if (!denylist_enforced || !kill_targets)
        return true;
    if (str_eql(pkg, ISOLATED_MAGIC)) {
        // Kill all matching isolated processes
        kill_process<&proc_name_match<str_starts>>(proc, true);
    } else {
        kill_process(proc);
    }
    return true;
}

static void clear_data() {
    pkg_to_procs_.reset(nullptr);
    app_id_to_pkgs_.reset(nullptr);
}

static bool ensure_data() {
    if (pkg_to_procs_)
        return true;

    LOGI("%s: initializing internal data structures\n", table_name);

    default_new(pkg_to_procs_);
    string select_from_cmd = string("SELECT * FROM ") + table_name;
    bool res = db_exec(select_from_cmd.data(), {}, [](StringSlice columns, const DbValues &values) {
        const char *package_name = nullptr;
        const char *process = nullptr;
        for (int i = 0; i < columns.size(); ++i) {
            const auto &name = columns[i];
            if (name == "package_name") {
                package_name = values.get_text(i);
            } else if (name == "process") {
                process = values.get_text(i);
            }
        }
        if (package_name && process) {
            add_hide_set(package_name, process, false);
        }
    });
    if (!res)
        goto error;

    default_new(app_id_to_pkgs_);
    rescan_apps_unlocked();

    return true;

error:
    clear_data();
    return false;
}

bool target_is_active(const char *target) {
    return str_eql(target, table_name);
}

// Direct table writes: used when the request targets a table the engine is not
// driving (the requested mode before the reboot that arms it, the first-enable
// seed, or the stock CLI while SuList is active). The engine picks the rows
// up at the next boot or rescan.
static int db_add(const char *table, const char *pkg, const char *proc) {
    if (get_app_id(pkg) == 0)
        return DenyResponse::INVALID_PKG;

    string select_sql = string("SELECT 1 FROM ") + table + " WHERE package_name=? AND process=?";
    bool exists = false;
    if (!db_exec(select_sql.data(), {pkg, proc},
                 [&exists](StringSlice, const DbValues &) { exists = true; }))
        return DenyResponse::ERROR;
    if (exists)
        return DenyResponse::ITEM_EXIST;

    string insert_sql = string("INSERT INTO ") + table + " (package_name, process) VALUES(?, ?)";
    if (!db_exec(insert_sql.data(), {pkg, proc}))
        return DenyResponse::ERROR;
    LOGD("%s add: [%s/%s] (applied at next boot)\n", table, pkg, proc);
    return DenyResponse::OK;
}

static int db_rm(const char *table, const char *pkg, const char *proc) {
    string select_sql = string("SELECT 1 FROM ") + table + " WHERE package_name=?";
    if (proc[0] != '\0')
        select_sql += " AND process=?";
    bool exists = false;
    if (!db_exec(select_sql.data(), proc[0] == '\0' ? DbArgs{pkg} : DbArgs{pkg, proc},
                 [&exists](StringSlice, const DbValues &) { exists = true; }))
        return DenyResponse::ERROR;
    if (!exists)
        return DenyResponse::ITEM_NOT_EXIST;

    string del_sql = string("DELETE FROM ") + table + " WHERE package_name=?";
    if (proc[0] != '\0')
        del_sql += " AND process=?";
    if (!db_exec(del_sql.data(), proc[0] == '\0' ? DbArgs{pkg} : DbArgs{pkg, proc}))
        return DenyResponse::ERROR;
    LOGD("%s rm: [%s%s%s] (applied at next boot)\n",
         table, pkg, proc[0] == '\0' ? "" : "/", proc);
    return DenyResponse::OK;
}

static void db_ls(int client, const char *table) {
    vector<pair<string, string>> rows;
    string select_sql = string("SELECT package_name, process FROM ") + table;
    bool ok = db_exec(select_sql.data(), {},
                      [&rows](StringSlice, const DbValues &values) {
        rows.emplace_back(values.get_text(0), values.get_text(1));
    });
    if (!ok) {
        write_int(client, static_cast<int>(DenyResponse::ERROR));
        close(client);
        return;
    }

    // A pure read. Stale rows are only dropped by the live purge of the
    // armed table (ls_list), never by a stock or dormant read.
    write_int(client, static_cast<int>(DenyResponse::OK));
    for (const auto &[pkg, proc] : rows) {
        if (pkg == ISOLATED_MAGIC || pkg == WEBVIEW_ZYGOTE_MAGIC || get_app_id(pkg) != 0) {
            string row = pkg + '|' + proc;
            write_int(client, static_cast<int>(row.size()));
            xwrite(client, row.data(), row.size());
        }
    }
    write_int(client, 0);
    close(client);
}

// The active-path core shared by the interactive add and the recommended
// seed; the table is explicit so the seed can address the table of the mode
// it is preparing.
static int add_list_to(const char *table, const char *pkg, const char *proc) {
    if (proc[0] == '\0')
        proc = pkg;

    if (!validate(pkg, proc))
        return DenyResponse::INVALID_PKG;

    if (!target_is_active(table))
        return db_add(table, pkg, proc);

    {
        mutex_guard lock(data_lock);
        if (!ensure_data())
            return DenyResponse::ERROR;
        int app_id = get_app_id(pkg);
        if (app_id == 0)
            return DenyResponse::INVALID_PKG;
        if (!add_hide_set(pkg, proc))
            return DenyResponse::ITEM_EXIST;
        auto it = pkg_to_procs.find(pkg);
        if (it == pkg_to_procs.end())
            return DenyResponse::ERROR;
        update_app_id(app_id, it->first, false);
    }

    // Add to database
    string sql = string("INSERT INTO ") + table_name + " (package_name, process) VALUES(?, ?)";
    if (!db_exec(sql.data(), {pkg, proc}))
        return DenyResponse::ERROR;
    return DenyResponse::OK;
}

// The interactive applet always operates on the applied table, so what the
// deny screen shows and edits is what the engine is driving; a mode switch
// becomes visible only at the reboot that arms it. The stock `--denylist`
// client always addresses the canonical denylist table.
static int add_list(const char *pkg, const char *proc, bool use_active) {
    return add_list_to(use_active ? table_name : "denylist", pkg, proc);
}

int add_list(int client, bool use_active) {
    string pkg = read_string(client);
    string proc = read_string(client);
    int res = add_list(pkg.data(), proc.data(), use_active);
    // A manual applet re-add clears the tombstone of the pair, so the
    // per-arm seed keeps the recommended row alive from the next arm on.
    if (use_active && res == DenyResponse::OK)
        clear_tombstone(table_name, pkg.data(), proc.empty() ? pkg.data() : proc.data());
    return res;
}

// Any explicit removal of a recommended row, through the applet or a stock
// client, leaves a tombstone so the per-arm seed never brings it back. A
// package-level removal tombstones every recommended pair of the package.
// That includes ZygiskNext's boot-time gms/vending removals through
// `magisk --denylist rm`, which are respected instead of re-seeded at the
// next arm. "Add recommended" or a manual re-add clears the tombstone.
static void tombstone_recommended(const char *table, const char *pkg, const char *proc) {
    auto list = recommend_list(table);
    if (proc[0] == '\0') {
        for (size_t i = 0; i < list.count; ++i) {
            const auto &e = list.entries[i];
            if (strcmp(e.pkg, pkg) == 0)
                set_tombstone(table, e.pkg, e.proc);
        }
    } else if (is_recommended_pair(table, pkg, proc)) {
        set_tombstone(table, pkg, proc);
    }
}

static int rm_list(const char *pkg, const char *proc, bool use_active) {
    // The applet always edits the applied table.
    const char *target = use_active ? table_name : "denylist";
    if (!target_is_active(target)) {
        int res = db_rm(target, pkg, proc);
        if (res == DenyResponse::OK)
            tombstone_recommended(target, pkg, proc);
        return res;
    }

    {
        mutex_guard lock(data_lock);
        if (!ensure_data())
            return DenyResponse::ERROR;

        bool remove = false;

        auto it = pkg_to_procs.find(pkg);
        if (it != pkg_to_procs.end()) {
            if (proc[0] == '\0') {
                update_app_id(get_app_id(pkg), it->first, true);
                pkg_to_procs.erase(it);
                remove = true;
                LOGI("%s rm: [%s]\n", table_name, pkg);
            } else if (it->second.erase(proc) != 0) {
                remove = true;
                LOGI("%s rm: [%s/%s]\n", table_name, pkg, proc);
                if (it->second.empty()) {
                    update_app_id(get_app_id(pkg), it->first, true);
                    pkg_to_procs.erase(it);
                }
            }
        }

        if (!remove)
            return DenyResponse::ITEM_NOT_EXIST;
    }

    string sql = string("DELETE FROM ") + table_name + " WHERE package_name=?";
    if (proc[0] != '\0')
        sql += " AND process=?";
    if (!db_exec(sql.data(), proc[0] == '\0' ? DbArgs{pkg} : DbArgs{pkg, proc}))
        return DenyResponse::ERROR;

    tombstone_recommended(table_name, pkg, proc);
    return DenyResponse::OK;
}

int rm_list(int client, bool use_active) {
    string pkg = read_string(client);
    string proc = read_string(client);
    return rm_list(pkg.data(), proc.data(), use_active);
}

void ls_list(int client, bool use_active) {
    // The applet always lists the applied table.
    const char *target = use_active ? table_name : "denylist";
    // The stock client is read-only and never takes the live path, so a
    // plain `--denylist ls` can never purge rows; the purge and the live
    // snapshot stay with the applet the manager calls.
    if (!use_active || !target_is_active(target)) {
        db_ls(client, target);
        return;
    }
    {
        mutex_guard lock(data_lock);
        if (!ensure_data()) {
            write_int(client, static_cast<int>(DenyResponse::ERROR));
            return;
        }

        set<string> users = get_users();
        set<string> pkgs_to_rm;
        set<string> isolated_procs_to_rm;

        // Find the packages that are not installed and remove them from list
        for (const auto &[pkg, procs] : pkg_to_procs) {
            // Isolated process
            if (pkg == ISOLATED_MAGIC) {
                // Find the isolated processes not associated with any app id
                for (const auto &proc : procs) {
                    bool associated = false;
                    for (const auto &[app_id, pkgs] : app_id_to_pkgs) {
                        for (const auto &pkg_ : pkgs) {
                            if (str_starts(proc, pkg_)) {
                                associated = true;
                                break;
                            }
                        }
                        if (associated)
                            break;
                    }
                    if (!associated)
                        isolated_procs_to_rm.insert(proc);
                }
                continue;
            }

            // For every package name of app
            bool installed = false;
            for (const auto &user : users) {
                string app_data_dir = string(APP_DATA_DIR) + "/" + user + "/" + pkg;
                if (access(app_data_dir.data(), F_OK) == 0) {
                    installed = true;
                    break;
                }
            }
            // The recommended rows survive "Clear data" (the data dir can
            // vanish while the package is still installed); the purge only
            // drops apps that are really gone.
            if (!installed && !recommended_pkg(table_name, pkg.data()))
                pkgs_to_rm.insert(pkg);
        }

        string sql = string("DELETE FROM ") + table_name + " WHERE package_name=?";
        for (const auto &pkg : pkgs_to_rm) {
            if (auto it = pkg_to_procs.find(pkg); it != pkg_to_procs.end()) {
                update_app_id(get_app_id(it->first), it->first, true);
                pkg_to_procs.erase(it);
                // Name the list refresh as the deleter, so a vanished row
                // is not mistaken for a client rm.
                LOGD("%s purge: [%s]\n", table_name, pkg.data());
            }
            db_exec(sql.data(), {pkg.data()});
        }

        if (auto it = pkg_to_procs.find(ISOLATED_MAGIC); it != pkg_to_procs.end()) {
            string del_sql = string("DELETE FROM ") + table_name + " WHERE package_name=? AND process=?";
            for (const auto &proc : isolated_procs_to_rm) {
                if (it->second.erase(proc) != 0) {
                    LOGI("%s rm: [%s/%s]\n", table_name, ISOLATED_MAGIC, proc.data());
                    if (it->second.empty()) {
                        pkg_to_procs.erase(it);
                    }
                }
                db_exec(del_sql.data(), {ISOLATED_MAGIC, proc.data()});
            }
        }

        write_int(client, static_cast<int>(DenyResponse::OK));

        for (const auto &[pkg, procs] : pkg_to_procs) {
            for (const auto &proc : procs) {
                write_int(client, pkg.size() + proc.size() + 1);
                xwrite(client, pkg.data(), pkg.size());
                xwrite(client, "|", 1);
                xwrite(client, proc.data(), proc.size());
            }
        }
    }
    write_int(client, 0);
    close(client);
}

void update_sulist_config(bool enable) {
    // Only the requested mode changes here; the engine keeps running in the
    // mode it armed with until the next boot.
    MagiskD::Get().set_db_setting(DbEntryKey::SulistConfig, enable ? 1 : 0);
}

// Runs in signal context: only async-signal-safe calls, no allocation.
// Restore the default action and re-raise so the tombstone is still
// generated.
static void deny_fatal_handler(int sig) {
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_fatal_handler() {
    struct sigaction act{};
    act.sa_handler = deny_fatal_handler;
    sigfillset(&act.sa_mask);
    sigaction(SIGSEGV, &act, nullptr);
    sigaction(SIGABRT, &act, nullptr);
    sigaction(SIGBUS, &act, nullptr);
}

// HyperOS diagnostic. The built-in Zygisk only reaches hyos_spawner through
// the shim, which magiskinit stages whenever the spawner exists; the opt-in
// marker is checked by the shim itself at spawner start. Report all three
// states once per boot so the on-device bring-up has a clear trace in
// magisk.log.
static void log_hyos_status() {
    if (!MagiskD::Get().zygisk_enabled())
        return;
    if (access("/system_ext/bin/hyos_spawner", F_OK) != 0)
        return;
    if (access("/dev/hyos_shim.so", F_OK) != 0) {
        LOGD("HyOS: hyos_spawner detected, the built-in Zygisk does not run in it "
             "(create /data/adb/hyos_shim_enable and reboot to enable the shim)\n");
        return;
    }
    if (access("/data/adb/hyos_shim_enable", F_OK) == 0) {
        LOGD("HyOS: hyos_spawner detected, the Zygisk shim is installed and enabled\n");
    } else {
        LOGD("HyOS: hyos_spawner detected, the Zygisk shim is installed but not enabled "
             "(create /data/adb/hyos_shim_enable and reboot to enable it)\n");
    }
}

int enable_deny() {
    if (denylist_enforced) {
        return DenyResponse::OK;
    } else {
        mutex_guard lock(data_lock);

        if (access("/proc/self/ns/mnt", F_OK) != 0) {
            LOGW("The kernel does not support mount namespace\n");
            sulist_enabled = false;
            table_name = "denylist";
            MagiskD::Get().set_db_setting(DbEntryKey::DenylistConfig, false);
            MagiskD::Get().set_db_setting(DbEntryKey::MagiskHideConfig, false);
            return DenyResponse::NO_NS;
        }

        if (!xopen_dir("/proc"))
            goto daemon_error;

        if (sulist_enabled) {
            LOGD("* Enable SuList\n");
        } else {
            LOGD("* Enable MagiskHide\n");
        }

        if (!ensure_data()) {
            goto daemon_error;
        }

        if (sulist_enabled) {
            // SystemUI and Settings keep the module mounts; the manager always
            // reaches Magisk.
            add_sulist_defaults();
        }

        denylist_enforced = true;

        // The handler stays unconditional: the deny engine (tables, rescan,
        // CLI) lives in magiskd in both modes.
        install_fatal_handler();

        // With the built-in Zygisk armed the monitor is not started.
        // update_deny_flags denies nobody under SuList, and under MagiskHide
        // the zygisk gate already reverts the listed processes at unshare,
        // so the monitor would only double-strip. With the built-in off, the
        // classic monitor runs in both modes.
        if (!MagiskD::Get().zygisk_enabled() && start_proc_monitor() != 0) {
            denylist_enforced = false;
            goto daemon_error;
        }

        // report the HyOS injection state in the boot log
        log_hyos_status();

    }

    // `magiskhide` is the master switch; `denylist` is pinned to 0 at
    // every DB open and must never be 1 together with `sulist`.
    MagiskD::Get().set_db_setting(DbEntryKey::MagiskHideConfig, true);
    return DenyResponse::OK;

daemon_error:
    sulist_enabled = false;
    table_name = "denylist";
    MagiskD::Get().set_db_setting(DbEntryKey::DenylistConfig, false);
    MagiskD::Get().set_db_setting(DbEntryKey::MagiskHideConfig, false);
    return DenyResponse::ERROR;
}

int disable_deny() {
    // The refusal follows the REQUESTED SuList mode (the DB key), not the
    // armed runtime flag: toggling SuList off unlocks the MagiskHide switch
    // immediately, and the engine keeps running in the mode it armed with
    // until the reboot.
    if (MagiskD::Get().get_db_setting(DbEntryKey::SulistConfig) != 0)
        return DenyResponse::SULIST_NO_DISABLE;

    if (denylist_enforced.exchange(false)) {
        LOGD("* Disable MagiskHide\n");
    }
    unsigned long mt = monitor_thread;
    if (mt != 0 && mt != (unsigned long) -1) {
        pthread_kill((pthread_t) mt, SIGTERMTHRD);
    }
    MagiskD::Get().set_db_setting(DbEntryKey::DenylistConfig, false);
    MagiskD::Get().set_db_setting(DbEntryKey::MagiskHideConfig, false);

    return DenyResponse::OK;
}

void initialize_denylist() {
    if (denylist_enforced)
        return;

    // `magiskhide` is the master switch; `denylist` is pinned to 0 at
    // every DB open (sqlite.cpp) and is never read here.
    bool enabled = MagiskD::Get().get_db_setting(DbEntryKey::MagiskHideConfig);

    if (!enabled)
        return;

    sulist_enabled = MagiskD::Get().get_db_setting(DbEntryKey::SulistConfig);
    table_name = sulist_enabled ? "sulist" : "denylist";

    enable_deny();

    if (!denylist_enforced)
        return;

    LOGI("* Enable %s\n", sulist_enabled ? "SuList" : "MagiskHide");

    // Every boot that arms a mode re-adds the missing recommended rows (both
    // modes arm through enable_deny). A tombstoned row stays removed,
    // whichever path — applet or stock client — removed it.
    seed_recommended(false);
}

bool is_uid_on_list(int uid) {
    mutex_guard lock(data_lock);
    if (!ensure_data())
        return false;
    auto it = app_id_to_pkgs.find(to_app_id(uid));
    // double check
    if (it == app_id_to_pkgs.end())
        return false;
    for (const auto &pkg : it->second) {
        if (auto list = pkg_to_procs.find(pkg); list != pkg_to_procs.end() && !list->second.empty())
            return true;
    }
    return false;
}

bool is_deny_target(int uid, string_view process, int max_len) {
    mutex_guard lock(data_lock);
    if (!ensure_data())
        return false;

    // /data/user is unreadable before unlock (FBE) and the map stays empty:
    // rescan lazily, the pre-unlock scan fails fast. boot-complete and the
    // packages.xml watcher refresh it too.
    if (app_id_to_pkgs.empty())
        rescan_apps_unlocked();

    int app_id = to_app_id(uid);

    // Match the manager by app id, not the data-dir scan, so SuList cannot lock
    // it out before /data/user is visible. Pre-unlock the lookup returns -1,
    // which is harmless since the manager only starts afterwards.
    int manager_app_id = MagiskD::Get().get_manager_uid(0);
    if (app_id == manager_app_id)
        return sulist_enabled ? true : false;

    if (app_id >= 90000) {
        if (auto it = pkg_to_procs.find(ISOLATED_MAGIC); it != pkg_to_procs.end()) {
            for (const auto &s : it->second) {
                if (s.length() > max_len && process.length() > max_len && str_starts(s, process))
                    return true;
                if (str_starts(process, s))
                    return true;
            }
        }
        return false;
    } else {
        auto it = app_id_to_pkgs.find(app_id);
        if (it == app_id_to_pkgs.end())
            return false;
        for (const auto &pkg : it->second) {
            if (auto list = pkg_to_procs.find(pkg); list != pkg_to_procs.end() && list->second.count(process))
                return true;
        }
        for (const auto &s : it->second) {
            if (s.length() > max_len && process.length() > max_len && str_starts(s, process))
                return true;
            if (s == process)
                return true;
        }
    }
    return false;
}

void scan_deny_apps() {
    bool armed;
    {
        // Capture the armed state inside the lock, seed after the release
        // (data_lock is not recursive and add_list_to takes it).
        mutex_guard lock(data_lock);
        if (pkg_to_procs_)
            rescan_apps_unlocked();
        armed = denylist_enforced.load();
    }
    // The app-id map is complete now (boot-complete), so this arm seeds the
    // recommended rows that initialize_denylist had to skip.
    if (armed)
        seed_recommended(false);
}

void update_deny_flags(int uid, rust::Str process, uint32_t &flags) {
    bool target = is_deny_target(uid, { process.begin(), process.end() });
    if (sulist_enabled) {
        // SuList governs root access only. With the built-in Zygisk armed
        // the engine injects modules into every process and denies nobody,
        // as Kitsune 27 does next to ZygiskNext, and the ptrace monitor is
        // not started either, so ProcessOnDenyList is never set here. There
        // is no whitelist branch: it made the zygisk gate and the monitor
        // strip every unlisted process twice, starving GMS (Play Integrity)
        // and keeping zygisk-detach away from the Play Store.
        // Non-listed processes additionally get the HideMounts bit, so the
        // built-in Zygisk reverts their Magisk mounts at unshare while still
        // loading modules. Listed processes and the manager keep their
        // mounts, so su keeps working for them; non-listed apps are denied
        // root by SuList anyway.
        if (!target) {
            flags |= +ZygiskStateFlags::HideMounts;
        }
    } else if (target) {
        flags |= +ZygiskStateFlags::ProcessOnDenyList;
    }
    if (denylist_enforced) {
        flags |= +ZygiskStateFlags::DenyListEnforced;
    }
}
