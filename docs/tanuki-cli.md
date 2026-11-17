# Tanuki Magisk CLI

This fork keeps the stock Magisk CLI verbatim and adds one applet and one option for its own hiding engine:

- `magisk magiskhide <action>` : the applet from Kitsune
- `magisk --magiskhide <action>` : the same actions as a top-level option

Both forms share one parser (`native/src/core/deny/cli.cpp`) and always act on the list the engine is currently driving: the **`denylist`** table in MagiskHide mode, the **`sulist`** table in SuList mode. The stock `magisk --denylist <action>` is untouched and always addresses the `denylist` table, whatever mode runs (see "Stock `--denylist`" below).

## Actions

| Action | What it does |
|--------|--------------|
| `status` | prints the MagiskHide status (`enforced` / `not enforced`) |
| `enable` | arms MagiskHide; the applet path also re-seeds the missing recommended rows, tombstone-aware |
| `disable` | disarms MagiskHide |
| `add PKG [PROC]` | adds a package (or one process of it) to the current mode's list |
| `rm PKG [PROC]` | removes it; any explicit removal writes a tombstone, so a seed does not bring it back |
| `ls` | prints the live list the engine is driving (`denylist`, or `sulist` in SuList mode) |
| `exec CMDs...` | runs commands in an isolated mount namespace with all Magisk unmounts applied |
| `recommended` | re-adds the compiled recommended pairs for the current mode; never removes anything |
| `sulist` | prints the SuList status |
| `sulist enable` / `sulist disable` | switches between SuList and MagiskHide mode (needs a reboot to take effect) |

Examples:

```sh
magisk magiskhide status          # is the engine armed?
magisk magiskhide ls              # what is hidden right now
magisk magiskhide add com.example.app
magisk magiskhide add com.example.app com.example.app:remote
magisk magiskhide rm com.example.app
magisk magiskhide recommended     # restore the recommended pairs
magisk magiskhide sulist          # which mode runs
magisk magiskhide sulist enable   # switch to SuList, then reboot
```

The manager drives these same actions through the applet path (`hide_handler`), which always operates on the live table of the running mode, never on the one the DB says will run at the next reboot.

## Stock `--denylist`

`magisk --denylist ...` keeps official Magisk's verbs and always addresses the `denylist` table, whatever the fork is doing with it:

- **MagiskHide mode** : that table *is* the live list. While the engine is armed, `add` and `rm` are refused, so a third-party script cannot edit the live list behind the manager's back; `ls` still reads it, and `enable` / `disable` still arm and disarm the engine, since they share `enable_deny` / `disable_deny` with the applet.
- **SuList mode** : the live list is `sulist`, so `denylist` sits unused and the stock client edits it exactly as upstream does.
- **Engine off** : the same, the stock client edits `denylist` freely.

The stock client never seeds. A removal of a recommended pair through the stock client leaves the same tombstone as one through the applet, so the next arm does not bring the row back.

Neither leftover the fork carries is the live list. Upstream's `denylist` *setting*, the "enforce denylist" switch, is pinned to 0 at every database open, `magiskhide` being the fork's master switch; Kitsune 27.2's `hidelist` table is not read by the engine at all, it is imported once from a pre-fork database and written back so a rollback to Kitsune still finds its list.

## Protocol, seeds and tombstones

The manager and the applet talk to the daemon with the request `HIDE` (value 15, the last enum entry, so every stock code keeps its value). Every arming boot re-adds the missing recommended rows in both modes:

- SuList defaults: `com.android.systemui`, `com.android.settings` and the current manager package, re-added in memory at every arm.
- MagiskHide recommended pairs: the GMS/GSF/vending families.

Every explicit removal is tombstoned: through the manager, through the applet, or through the stock client while it can still reach `denylist` (engine off, or SuList armed), Zygisk Next's boot-time `magisk --denylist rm` of gms/vending included, so the next arm does not bring those rows back.

Only "Add recommended" or a manual re-add clears a tombstone.

## Rescue commands

These are stock Magisk commands and all work on this fork:

- `magisk --remove-modules [-n]` : remove every module from an ADB shell and reboot (or not, with `-n`). The first resort when a module bootloops.
- **Safe Mode** : press and hold volume down when the boot animation starts, before Magisk's early key-combo detection point: modules are disabled for the next boot.
- `magisk --stop` : stop the daemon and remove its changes.
- `magisk --sqlite SQL` : run SQL against the Magisk database.
- `magisk --unlock-blocks` : clear BLKROSET on every block device.

The full stock tool set is documented in the [official tools reference](https://topjohnwu.github.io/Magisk/tools.html); everything there that is not listed on this page behaves identically.
