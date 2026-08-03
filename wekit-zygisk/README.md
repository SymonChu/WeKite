# WeKite Zygisk Module

WeKite can be loaded through Zygisk on a per-Android-user, per-package basis.
The module is disabled for every process immediately after installation.

## KernelSU WebUI

Open the WeKite module page in KernelSU to manage injection targets.

- The first page open scans every Android user and adds every installed package
  matching `PackageNames.isWeChat` (`com.tencent.mm*`) as a disabled target.
- Package discovery uses KernelSU's root-shell `exec` API to run
  `/system/bin/pm list users` and `pm list packages --user <id>`; it does not
  use KernelSU's `listPackages` or `getPackagesInfo` APIs.
- Enabling one instance injects its main process and every process named
  `<package>:...` for that same Android user at the next process launch.
- Refresh scans all Android users again, replaces the package membership with
  the current result, preserves switches for surviving rows, and disables newly
  discovered rows. The WebUI intentionally has no manual add or delete action.

## WeChat Monet overlays

The module ships static RRO overlays that theme WeChat natively:

- **Monet 基础主题** — replaces the brand green with the wallpaper accent
- **现代圆角气泡 / 经典气泡** — modern rounded and classic chat bubble styles
  (switch via the module Action menu, volume key)
- **多场景圆角** — rounded corners for input bar / message quote / pay keyboard
- **纯色底栏** — solid-color bottom tab bar

Overlays are installed only when WeChat is the Play-store 8.0.72 build
(versionCode 3083/3084); otherwise they are skipped gracefully.
