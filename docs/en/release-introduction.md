# Current delivery and historical evidence

Updated: 2026-11-10. This English entry describes the maintained build358 source and links historical records separately. It is not a remote-publication receipt.

> **build358 `0.2.1-rc.5-20261111.0800-dsh0.2.2-rc.1`.** This build is the release-engineering maintenance build after build357 (T141, `4e5b17e47`). The production release pipeline now splits build and sign into separate jobs so the unsigned compile stage can reuse the dependency cache, builds test packages in an unsigned-compile / sign two-stage flow, enables an npm cache for inputs dependencies, preinstalls android-sdk on the runner, and slims the evidence bundle to the entries needed for verification; the dry run measured 10m56s against the previous pipeline's 18m48s build357 production run, about 42% shorter including the publish stage. **There are no app functional changes**: build357 and build358 have identical user-visible functionality, and app source, resources, copy and interaction are untouched, so this is a release-engineering build only. T141 is not inside the `runtime-descriptor` `launcherInputs`, so `runtimeId` stays **`c780bc22`** (205 inputs) and **a 357→358 overwrite does not enter the runtime maintenance page** (if it appears, complete it as prompted). Fixed inputs stay frozen at the locked `fixed-inputs-20261105e` with bundled `dsh-web-mobile` 3.3.5 still default-enabled. versionCode 358, package `io.github.sunsetrne.dsha`, single Standard, Mod-owned release certificate, bundled DSH 0.2.2-rc.1. build357 is already released (tag `dsha-a-su-build357-20261111-060000` @ `678f19c3c`) and is not yet device-validated; **build358 is not yet device-validated either** — with no functional changes, acceptance is mainly a 357→358 overwrite with data intact and READY plus confirming the first launch does not enter maintenance, with only `.debug` / `.dev` packages and no formal package or formal data touched. build344–build357 formal-package overwrite acceptance and the device acceptance of the released build338–build357 follow their own records. See the [build358 notes](../releases/build358-changes.en.md) and [CHANGELOG](../../CHANGELOG.md).

Current fixed inputs are pinned by `ci/sunset-inputs.lock.json` (`fixed-inputs-20261105e`, 716,787,167 B, SHA-256 `be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5`). The signed APK, complete source ZIP, unit-test evidence and release assets are produced and bound by the CI release flow; no test totals or publication state are prefilled here.

F10–F13 and build190 records retain their original identity and scope. Their package names, version codes, DSH versions, test counts and device statements are historical evidence, not this build's acceptance.

## Confirmed behavior described by the current guides

- Actual directory-controller composition retains backend/Gateway checks, Config/name and named-class behavior, without deleting workspaces, sessions or attachments. [Operations and boundaries](plugins.md).
- FileUploads owns an exact registration object instead of comparing Cordis-wrapped callbacks. Duplicate rejection, old-disposer fencing, upload intake and Session receipts remain. [Runtime and service details](runtime-data-plugin-architecture.md).
- LIVE/UNKNOWN/EXITED are distinct. UNKNOWN retains the same DOM/Gecko session under a native status panel without ready authority. Same-record/generation/instance/URL/port kernel → HTTP → kernel verification creates a new registration; old tickets never revive and conversations/backend starts are not replayed. [Startup and recovery](startup-and-diagnostics.md).

- [Plugin discovery and control](plugin-runtime-and-permissions.md) use bounded asynchronous full-content proof, readonly queries outside the profile writer lock, and a final actual-target proof for deletion. Native toggles show verified persisted intent before an independently coalesced refresh; restart remains necessary for loading. The cooperative 30-second budget includes queue/preparation boundaries/execution, but cannot preempt a single system IO, monitor, or Binder operation. Unknown process exit preserves the original barrier.
- [Strict LAN recovery](lan.md) reuses the cookie verified by that recovery only after the second kernel identity check and exact current generation/URL/port checks. It does not repeat exchange or ordinary post-authentication startup work. LAN disabled means no listener; a bind failure does not undo successful local adoption.
- [Automatic-port investigation](ports-and-networking.md) has not established automatic ports as the plugin freeze's cause. Native plugin helpers use guest commands, and controlled real voice-plugin toggles retained the same HTTP object/port. Automatic `0` remains; actual port changes still have independent browser-origin rules.
- [Welcome persistence](startup-and-diagnostics.md) uses bounded official Host describe verification and continuing observation of the same mirror. Dismissal requires actual persisted confirmation. Explicit revision conflicts have bounded retries of the same field; unknown results are not automatically resent. An unsaved value remains an error. Browser negative controls do not identify a specific phone screenshot's error code.

## Historical checkpoints

| Record | What it establishes |
|---|---|
| Published F10 reference, commit `087724c` | Reference source state preceding this documentation/fix work; its own APK/results remain attached to that historical identity |
| [F11 software checkpoint](../releases/v0.2.1-rc.2-build190-fix11-20261021.md) | An unpublished local software checkpoint. Its 1157 candidate version, test counts, artifacts, and prepared Release URL do not establish F12 or publication |
| [Build190 fix1](../releases/v0.2.1-rc.2-build190-fix1-20261019.md) | Recorded checks on its exact historical package and paths, including limited phone evidence; not acceptance of a new APK |
| [Build170](../releases/v0.2.21-build170-20261009.md) | Historical public release and its own acceptance scope, not this private delivery |

Historical reports remain in their original language and bytes (or documented line-ending-only readable views). Their “current”, “final”, “passed”, and “not uploaded” statements refer to their original time. Original local `app/build` evidence, device logs, and screenshots may not be distributed. The [index](index.md) and [gap matrix](documentation-gap-matrix.md) explain the exact historical documentation closure and evidence classes.

## What English documentation verifies

The English set supplies substantive counterparts for maintained operations, architecture, permissions, data/plugin behavior, and engineering workflow. It preserves real command/path identifiers and links to actual source. Translation/link checks verify documentation structure, file presence, and distribution metadata. They do not build an APK or rerun Android, browser, model, network, or long-duration acceptance.

Device/ADB acceptance, real-platform account login, model requests, actual Wi-Fi changes, and new long-duration tests remain outside this documentation work. Host logic, browser fixtures, and APK inspection must be reported separately from phone execution. Only the final owner can bind completed checks to a frozen F13 source/APK/ZIP and actual remote state.
