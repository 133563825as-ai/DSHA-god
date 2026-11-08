# Current delivery and historical evidence

Updated: 2026-11-08. This English entry describes the maintained build345 source and links historical records separately. It is not a remote-publication receipt.

> **build345 `0.2.1-rc.5-20261108.1500-dsh0.2.2-rc.1`.** This build is the UI slimming and fix batch after build344 (pure UI, six commits): the Settings page drops its top banner and entry subtitles, moves theme / language state to trailing values and keeps the power-saving row height constant; Device grants collapses long explanations by default, de-duplicates the Root note and turns channel / system-permission / sensitive-capability / virtual-screen state into same-line `ShellStatusPill` pills; "Manage file access" goes back from a disclosure to opening the system "All files access" page from the whole row or its pill; `DisclosureCard` now detaches the action view before reusing it, fixing the second-expand crash; the switch off-track uses the Material `trackTint` near-white `shell_surface` (the component ignores `android:track`) with the theme accent when on; Data & backup drops the duplicated hero summary/expanded section and no longer expands NEVER by default or stacks the old one-tap backup receipt; GitHub login shortens long values onto one line, and its guide dialog becomes custom content (monospace selectable command, copy-command / copy-authorization-link buttons, one-tap terminal kept). This is a pure UI fix and slimming build: no managed input changes and no run-root script changes; runtimeId stays `f65796a2`, fixed inputs stay frozen, and the bundled `dsh-web-mobile` stays upstream 3.3.1 and default-enabled. VersionCode 345, package `io.github.sunsetrne.dsha`, single Standard, Mod-owned release certificate, bundled DSH 0.2.2-rc.1. build338–344 device acceptance is still pending with the user, and the 344→345 first overwrite and formal-package acceptance of build344 / build345 are **not yet validated** (this batch was verified with the SunsetTest package for the file-access row, the switch off-track and the GitHub login dialog copying; that is test-package evidence only). See the [build345 notes](../releases/build345-changes.en.md) and [CHANGELOG](../../CHANGELOG.md).

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
