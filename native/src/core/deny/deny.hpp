#pragma once

#include <pthread.h>
#include <atomic>
#include <functional>
#include <string_view>

// MagiskHide/SuList definitions shared by the C++ deny island; the Rust side
// only forwards its requests and responses.

#define ISOLATED_MAGIC "isolated"
#define WEBVIEW_ZYGOTE_MAGIC "webview_zygote"
#define WEBVIEW_ZYGOTE_UID 1053

#define SIGTERMTHRD SIGUSR1

namespace DenyRequest {
enum : int {
    ENFORCE,
    DISABLE,
    ADD,
    REMOVE,
    LIST,
    STATUS,
    SULIST_STATUS,
    ENFORCE_SULIST,
    DISABLE_SULIST,
    // Seed the recommended rows for the requested mode. Appended so the
    // wire values of the existing requests do not change; the stock CLI
    // never sends it.
    RECOMMENDED,

    END
};
}

namespace DenyResponse {
enum : int {
    OK,
    ENFORCED,
    NOT_ENFORCED,
    ITEM_EXIST,
    ITEM_NOT_EXIST,
    INVALID_PKG,
    NO_NS,
    ERROR,
    SULIST_ENFORCED,
    SULIST_NOT_ENFORCED,
    SULIST_NO_DISABLE,

    END
};
}

// CLI entries. use_active marks the magiskhide applet, which always operates
// on the applied table (K27 parity); the stock denylist CLI always addresses
// the canonical denylist table.
int enable_deny();
int disable_deny();
int add_list(int client, bool use_active = true);
int rm_list(int client, bool use_active = true);
void ls_list(int client, bool use_active = true);
// True when the engine is currently driving the given table; the stock CLI
// is made read-only on a live denylist.
bool target_is_active(const char *target);

// Shared state and helpers
extern std::atomic<bool> sulist_enabled;
void update_sulist_config(bool enable);
// Re-add the missing recommended rows of the applied table. The per-arm seed
// skips the rows the user removed explicitly (tombstones); a full request
// ("Add recommended") clears the tombstones first.
void seed_recommended(bool full_request);
bool is_uid_on_list(int uid);
void rescan_apps();
void crawl_procfs(const std::function<bool(int)> &fn);

// Process monitor (ptrace.cpp)
extern std::atomic<unsigned long> monitor_thread;
void proc_monitor();
int start_proc_monitor();

// Mount helpers (revert.cpp)
void revert_daemon(int pid, int client = -1);

bool proc_context_match(int pid, std::string_view context);
void *logcat(void *arg);
extern bool logcat_exit;
