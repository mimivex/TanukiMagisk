#include <sys/wait.h>
#include <sys/mount.h>
#include <sys/socket.h>

#include <core.hpp>

#include "deny.hpp"

using namespace std;

[[noreturn]] static void denylist_usage() {
    fprintf(stderr,
R"EOF(DenyList Config CLI

Usage: magisk --denylist [action [arguments...] ]
Actions:
   status          Return the enforcement status
   enable          Enable denylist enforcement
   disable         Disable denylist enforcement
   add PKG [PROC]  Add a new target to the denylist
   rm PKG [PROC]   Remove target(s) from the denylist
   ls              Print the current denylist
   exec CMDs...    Execute commands in isolated mount
                   namespace and do all unmounts

)EOF");
    exit(1);
}

[[noreturn]] static void magiskhide_usage() {
    fprintf(stderr,
R"EOF(MagiskHide Config CLI

Usage: magiskhide [action [arguments...] ]
Actions:
   status          Return the MagiskHide status
   enable          Enable MagiskHide
   disable         Disable MagiskHide
   add PKG [PROC]  Add a new target to the hidelist (sulist)
   rm PKG [PROC]   Remove target(s) from the hidelist (sulist)
   ls              Print the current hidelist (sulist)
   exec CMDs...    Execute commands in isolated mount
                   namespace and do all unmounts
   recommended     Add the recommended apps for the current mode
Magisk Delta specific Actions:
   sulist          Return the SuList status
   sulist [enable|disable]
                   Enable or disable SuList (need reboot)

)EOF");
    exit(1);
}

[[noreturn]] static void usage(bool stock) {
    if (stock)
        denylist_usage();
    else
        magiskhide_usage();
}

// use_active marks the magiskhide applet, which always operates on the
// applied table; the stock client always targets the canonical denylist, so
// official Magisk sees its own list.
static void handle_list_request(int client, bool use_active) {
    if (client < 0) {
        revert_unmount();
        return;
    }

    int req = read_int(client);
    int res = DenyResponse::ERROR;

    // While the engine drives the denylist it owns the list; the stock
    // client becomes read-only so a third-party script cannot empty the
    // table behind the user's back. The manager uses the magiskhide applet,
    // which is never refused. With MagiskHide off the stock client keeps
    // editing the dormant table as upstream does.
    const bool stock_readonly = !use_active && denylist_enforced && target_is_active("denylist");

    switch (req) {
    case DenyRequest::ENFORCE:
        res = enable_deny();
        // Every arm through the applet re-adds the missing recommended rows;
        // a row the user removed stays away (tombstone). The stock
        // `--denylist enable` never seeds.
        if (use_active && res == DenyResponse::OK)
            seed_recommended(false);
        break;
    case DenyRequest::DISABLE:
        res = disable_deny();
        break;
    case DenyRequest::ADD:
        // Refused while the engine owns the live denylist.
        if (stock_readonly)
            break;
        res = add_list(client, use_active);
        break;
    case DenyRequest::REMOVE:
        // Refused while the engine owns the live denylist.
        if (stock_readonly)
            break;
        res = rm_list(client, use_active);
        break;
    case DenyRequest::LIST:
        ls_list(client, use_active);
        return;
    case DenyRequest::ENFORCE_SULIST:
        update_sulist_config(true);
        // The SuList is seeded by the boot that arms it; a mode switch never
        // writes to the dormant table.
        res = DenyResponse::OK;
        break;
    case DenyRequest::DISABLE_SULIST:
        update_sulist_config(false);
        res = DenyResponse::OK;
        break;
    case DenyRequest::RECOMMENDED:
        // Full request for the applied table: it clears the tombstones and
        // re-adds every recommended row; it never removes anything.
        seed_recommended(true);
        res = DenyResponse::OK;
        break;
    case DenyRequest::STATUS:
        res = denylist_enforced ? DenyResponse::ENFORCED : DenyResponse::NOT_ENFORCED;
        break;
    case DenyRequest::SULIST_STATUS:
        res = sulist_enabled ? DenyResponse::SULIST_ENFORCED : DenyResponse::SULIST_NOT_ENFORCED;
        break;
    default:
        // Unknown request code
        break;
    }
    write_int(client, res);
    close(client);
}

void denylist_handler(int client) {
    handle_list_request(client, false);
}

void hide_handler(int client) {
    handle_list_request(client, true);
}

static int deny_cli(size_t argc, const char *const *argv, bool stock) {
    if (argc == 0)
        usage(stock);

    int req = -1;
    if (argv[0] == "enable"sv)
        req = DenyRequest::ENFORCE;
    else if (argv[0] == "disable"sv)
        req = DenyRequest::DISABLE;
    else if (argv[0] == "add"sv)
        req = DenyRequest::ADD;
    else if (argv[0] == "rm"sv)
        req = DenyRequest::REMOVE;
    else if (argv[0] == "ls"sv)
        req = DenyRequest::LIST;
    else if (argv[0] == "status"sv)
        req = DenyRequest::STATUS;
    else if (argv[0] == "recommended"sv && !stock) {
        // The stock CLI does not know this action; let it fall through to
        // usage().
        req = DenyRequest::RECOMMENDED;
    } else if (argv[0] == "sulist"sv) {
        // SuList has no stock equivalent, so a stock client only gets usage.
        if (stock)
            usage(stock);
        if (argc >= 2) {
            if (argv[1] == "enable"sv)
                req = DenyRequest::ENFORCE_SULIST;
            else if (argv[1] == "disable"sv)
                req = DenyRequest::DISABLE_SULIST;
            else
                usage(stock);
        } else {
            req = DenyRequest::SULIST_STATUS;
        }
    } else if (argv[0] == "exec"sv && argc > 1) {
        vector<char *> args;
        for (size_t i = 1; i < argc; ++i)
            args.push_back(const_cast<char *>(argv[i]));
        args.push_back(nullptr);
        xunshare(CLONE_NEWNS);
        xmount(nullptr, "/", nullptr, MS_PRIVATE | MS_REC, nullptr);
        revert_unmount();
        execvp(args[0], args.data());
        exit(1);
    } else {
        usage(stock);
    }

    if ((req == DenyRequest::ADD || req == DenyRequest::REMOVE) && argc < 2)
        usage(stock);

    // Send request
    owned_fd fd = connect_daemon(stock ? RequestCode::DENYLIST : RequestCode::HIDE);
    if (fd < 0) exit(1);
    write_int(fd, req);
    if (req == DenyRequest::ADD || req == DenyRequest::REMOVE) {
        write_string(fd, argv[1]);
        write_string(fd, argc > 2 ? argv[2] : "");
    }

    // Get response
    int res = read_int(fd);
    if (res < 0 || res >= DenyResponse::END)
        res = DenyResponse::ERROR;
    switch (res) {
    case DenyResponse::NOT_ENFORCED:
        fprintf(stderr, stock ? "Denylist is not enforced\n" : "MagiskHide is disabled\n");
        goto return_code;
    case DenyResponse::ENFORCED:
        fprintf(stderr, stock ? "Denylist is enforced\n" : "MagiskHide is enabled\n");
        goto return_code;
    case DenyResponse::SULIST_NOT_ENFORCED:
        fprintf(stderr, "SuList is not enforced\n");
        return 1;
    case DenyResponse::SULIST_ENFORCED:
        fprintf(stderr, "SuList is enforced\n");
        return 0;
    case DenyResponse::ITEM_EXIST:
        fprintf(stderr, stock ? "Target already exists in denylist\n"
                              : "Target already exists in hidelist\n");
        goto return_code;
    case DenyResponse::ITEM_NOT_EXIST:
        fprintf(stderr, stock ? "Target does not exist in denylist\n"
                              : "Target does not exist in hidelist\n");
        goto return_code;
    case DenyResponse::NO_NS:
        fprintf(stderr, "The kernel does not support mount namespace\n");
        goto return_code;
    case DenyResponse::INVALID_PKG:
        fprintf(stderr, "Invalid package / process name\n");
        goto return_code;
    case DenyResponse::ERROR:
        fprintf(stderr, stock ? "deny: Daemon error\n" : "hide: Daemon error\n");
        return -1;
    case DenyResponse::SULIST_NO_DISABLE:
        fprintf(stderr, "MagiskHide cannot be disabled because SuList is enforced\n");
        return -1;
    case DenyResponse::OK:
        break;
    default:
        __builtin_unreachable();
    }

    if (req == DenyRequest::LIST) {
        string out;
        for (;;) {
            read_string(fd, out);
            if (out.empty())
                break;
            printf("%s\n", out.data());
        }
    }

return_code:
    return req == DenyRequest::STATUS ? res != DenyResponse::ENFORCED : res != DenyResponse::OK;
}

int denylist_cli(rust::Vec<rust::String> &args) {
    vector<string> storage;
    storage.reserve(args.size());
    for (auto &arg : args)
        storage.emplace_back(arg.c_str());

    vector<const char *> argv;
    argv.reserve(storage.size());
    for (auto &arg : storage)
        argv.push_back(arg.c_str());

    return deny_cli(argv.size(), argv.data(), true);
}

int magiskhide_cli(rust::Vec<rust::String> &args) {
    vector<string> storage;
    storage.reserve(args.size());
    for (auto &arg : args)
        storage.emplace_back(arg.c_str());

    vector<const char *> argv;
    argv.reserve(storage.size());
    for (auto &arg : storage)
        argv.push_back(arg.c_str());

    return deny_cli(argv.size(), argv.data(), false);
}

int magiskhide_main(int argc, char *argv[]) {
    if (argc < 2)
        usage(false);

    vector<const char *> args;
    args.reserve(argc - 1);
    for (int i = 1; i < argc; ++i)
        args.push_back(argv[i]);

    return deny_cli(args.size(), args.data(), false);
}
