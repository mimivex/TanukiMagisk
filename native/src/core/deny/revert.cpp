/*
 * Kitsune Magisk deny engine (MagiskHide/SuList), ported from Kitsune
 * Magisk 27.2 by HuskyDG. GPL-3.0; see LICENSE and THIRD_PARTY_LICENSES.md.
 */
#include <signal.h>
#include <unistd.h>

#include <base.hpp>
#include <core.hpp>

#include "deny.hpp"

using namespace std;

// Revert the Magisk mounts of another process. The actual unmount happens in
// Rust (mount.rs), which enters the target mount namespace and detaches every
// mount created by Magisk. Do it in a child process so the daemon itself keeps
// its own namespace untouched.
void revert_daemon(int pid, int client) {
    if (fork_dont_care() == 0) {
        revert_unmount(pid);
        if (client >= 0) {
            write_int(client, DenyResponse::OK);
        } else if (client == -1) {
            // Resume the target, which is stopped in ptrace
            kill(pid, SIGCONT);
        }
        _exit(0);
    }
}
