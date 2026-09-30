# SmsForwarder (fork) — build/disguise notes

fork of pppscn/SmsForwarder (BSD-3). this fork exists to ship a disguised build:
app label = "System Services" (系统服务/系統服務 in zh locales) + slate gear icon,
so devices not covered by an OEM hide-workflow still look boring.

## build (windows, scoop toolchain)

- **JDK 17 required** — wrapper pins Gradle 7.3.3 (Java ≤17); JDK 21 present on
  this machine fails. `scoop install temurin17-jdk`, then
  `$env:JAVA_HOME='C:\Users\Admin\scoop\apps\temurin17-jdk\current'` before gradlew.
- **upstream gradle.properties asks `-Xmx16g`** → OOM-kills the daemon during
  `minifyDebugWithR8` on this 16GB machine (hs_err: native mmap failed). changed
  to `-Xmx3g` / kotlin 512m — builds clean, do not raise back.
- **signing**: build expects `keystore/keystore.properties` + keystore file
  (gitignored). create once with keytool; without it `packageDebug` dies with
  `SigningConfig "release" is missing required property "storeFile"` (both
  variants sign with the release config when `isNeedPackage=true`).
- outputs land in `build/app/outputs/apk/debug/` (per-ABI + universal), ~19MB.
- build cmd: `.\gradlew.bat assembleDebug --console=plain` (~2min warm).

## install (MIUI/HyperOS gotcha)

- `adb install` AND `adb shell pm install` both fail
  `INSTALL_FAILED_USER_RESTRICTED` until **Settings → Additional settings →
  Developer options → "Install via USB"** is ON (mi account required for the
  toggle). then push + `pm install` from `/data/local/tmp` works headless.
  no root path exists for this (root is forbidden in our workflow anyway).

## disguise edits (keep symmetric)

- label: 4 files `app/src/main/res/values{,-en,-zh-rCN,-zh-rTW}/strings.xml` →
  `app_name` — change all four together.
- icon: regenerate via
  `automata-private\android-device-control\make-gear-icon.ps1 -RepoPath <this repo>`
  (writes 5 mipmap densities).
- package id stays `cn.ppps.forwarder` — grants and listener component names
  depend on it, never change it.

## post-install grants (adb, silent)

```
pm grant cn.ppps.forwarder android.permission.READ_SMS
pm grant cn.ppps.forwarder android.permission.RECEIVE_SMS
pm grant cn.ppps.forwarder android.permission.READ_CALL_LOG
pm grant cn.ppps.forwarder android.permission.READ_CONTACTS
cmd notification allow_listener cn.ppps.forwarder/.service.NotificationService
```
`allow_listener` works on Android 13 sideloaded apps via adb (restricted-settings
gate bypassed) — verify with `settings get secure enabled_notification_listeners`.
signature differs from upstream release APK → installing this build requires
uninstalling the official one first (wipes its data).
