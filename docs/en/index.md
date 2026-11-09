# Documentation index and maintenance rules


Current source: `0.2.1-rc.5-20261110.0700-dsh0.2.2-rc.1` / build352 / `io.github.sunsetrne.dsha` / DSH-A-Su (`main`). This build carries three lines. T135 fixes plugin registration on the first start after an overwrite upgrade: the registration budget is tiered by managed-asset identity (90 s normal / 240 s first start; the host reads the `runtime-descriptor` `runtimeId` and the guest reads `managed-assets-v2`, writing `register-managed-asset-id` on success), `--clear-stale-profile-lock` removes only confirmed-exited holders' locks and `plugin-manager list` reports `PLUGIN_PROFILE_LOCK_STALE` (retryable); `StartupRecoveryState` gains `Kind.REGISTER` and keeps only the actionable conclusion (no raw `exit=` / JSON), the registration command carries `hostElapsedMs` / the last `REGISTER_TIMING` stage and `GUEST_TIMING_MISMATCH`, `RuntimeTools` writes a cross-process `.runtime-tools-prepared` completion stamp (reused only on identical identities), and the maintenance 5/5 check gains a `BUILTIN_REGISTER_OK` registration validation; the report explicitly does not attribute the 479409 ms to any single sub-stage. T134 adds `/app/extdev/watch` (`mode=events` / `window`, continuously sampling events inside one dedicated ADB session and returning the foreground package / Activity, a digest bound to the target generation, `sequence` and `settle`, with `since` stringing calls into "wait for the page to change") and `operate observe=1` (a dispatched action attaches a 4-sample observation; an observation failure only writes `observationUnavailable`, while `NOT_SENT` / `POLICY_BLOCKED` / `UNKNOWN` semantics are unchanged); device measurements are logcat events p50 123 ms, `dumpsys window` 128 ms, `screencap` 922 ms, and Phase C is not implemented. T133 is the build351 first-start failure root-cause report (documentation only), recording that the 479409 ms is inconsistent with the host 90 s budget and that attribution awaits field evidence. The merged tree recomputes the managed runtime `runtimeId` to **`b8c59df9`** (205 inputs); fixed inputs stay frozen at the locked 20261105e. **build352 is not yet device-validated**: acceptance is a 351→352 overwrite that succeeds with data intact and READY, the first-start registration (first-start wide budget hit / dead-holder lock cleanup / recovery-page classification / staged timing / `.runtime-tools-prepared` reuse / maintenance registration check) and `/app/extdev/watch` + `operate observe=1` (a new endpoint that needs item-by-item test-package acceptance on the PJD110); build350's T98 / T119, build351's T130 / T131 and build344–build351 formal-package overwrite acceptance remain pending with the user. The full host contracts group, full unit tests, lint and whole-tree format / documentation checks are executed by this release preparation (results in the worktree declaration). See the [build352 notes](../releases/build352-changes.en.md).

Build and acceptance records for earlier versions stay in [releases/](releases/); each version's conclusions hold only for its own commit and point in time.


## Current features and architecture

| Document | Purpose |
|---|---|
| [Project architecture handbook](project-architecture-handbook.md) | Main features, framework layers, entry classes, and change/test map |
| [ADB / Root / Shizuku permission architecture](device-permissions-architecture.md) | OS permissions, privileged channels, accessibility, application approval, services, revocation, and diagnosis |
| [Runtime, data, and plugin architecture](runtime-data-plugin-architecture.md) | Extraction/startup, normal/safe/recovery modes, managed updates, pnpm/plugin transactions, credentials, backup/restore |
| [Runtime engines](runtime-engines.md) | Current proot/proroot, historical DSHA v1.1.6, vendor versions, loaders/shims, exit supervision, and compatibility |
| [Ports and networking](ports-and-networking.md) | Automatic/manual ports, local fallback memory, separate bridge/ADB/LAN endpoints, and retry integration boundaries |
| [DNS configuration](dns-configuration.md) | Actual auto/ipv4/native differences, narrow Node lookup retry, glibc no-aaaa, and user choices |
| [Virtual-screen architecture](virtual-screen-architecture.md) | Core/Manager/tool routes, frame/node/anchor evidence, capture/input, closure/recovery, and regression map |
| [ADR 0001](adr/0001-build154-boundaries.md), [ADR 0002](adr/0002-current-threat-model.md), [ADR 0003](adr/0003-virtual-screen-lifecycle-and-input.md) | Architectural choices, permission/data boundaries, virtual-screen lifecycle, and consequences |
| [References and provenance](references-and-provenance.md) | Primary platform sources, upstream relationships, pinned versions/digests, licensing, and source limits |

## User and developer entry points

- [README](../../README.en.md), [development constraints](../../AGENTS.en.md), [build instructions](../../BUILD.en.md), [contribution workflow](../../CONTRIBUTING.en.md), and [handoff](handoff.md).
- [Browser/flavor behavior](browser-engines.md), [plugins](plugins.md), [virtual-screen setup](virtual-screen-guide.md), [Web files](web-file-sharing.md), [v5 backup format](backup-format-v5.md), and [configuration recovery](configuration-recovery-host.md).
- [Engineering standard](engineering-standard.md), [module map](module-map.md), [security model](security-model.md), [source provenance](source-provenance.md), [third-party notices](third-party-notices.md), and [download verification](download-verification.md).
- [Historical Standard notes](../android-standard.md) and [historical Low notes](../android-low.md) remain original records; use the current English browser/engine pages for present behavior.

## Operations and focused guides

| Scenario | English guides |
|---|---|
| First installation, component probing, targeted repair, maintenance, interrupted operations | [Installation and maintenance](install-and-maintenance.md), [startup and diagnostics](startup-and-diagnostics.md), [application/managed updates](update.md) |
| Engine/static-loader/seccomp, DNS, and port diagnosis | [Runtime/network choices](runtime-and-network.md), [engines](runtime-engines.md), [DNS](dns-configuration.md), [ports](ports-and-networking.md) |
| Device channels, 3090 protocol, LAN, files, system documents | [Device channels](device-channels.md), [bridge and files](bridge-and-files.md), [LAN](lan.md), [Web files](web-file-sharing.md) |
| One-click/manual/automatic backup, restore, snapshots, independent recovery | [Backup and restore](backup-and-restore.md), [configuration snapshots](config-snapshots.md), [independent recovery](emergency-recovery.md), [v5 format](backup-format-v5.md) |
| Plugin installation, system/user separation, protocol, permissions, restart | [Plugin operations](plugins.md), [plugin runtime and permissions](plugin-runtime-and-permissions.md) |
| White screen after an over-the-top upgrade / maintenance not starting / built-in plugins depending on host widgets | [Over-the-top upgrades, runtime maintenance, and plugin dependency contracts](overwrite-upgrade-and-maintenance.md) |
| External PR / external merge mandatory line-by-line review | [External PR and external merge review process](external-pr-review.md) |
| Temporary built-in fix: read hint for "file has not been read" (upstream #11977) | [dsh-fs-read-hint](fs-read-hint-plugin.md) |
| Temporary built-in fix: glob skips nested git worktrees (T45) | [dsh-fs-search-scope](fs-search-scope-plugin.md) |
| PTY/simple terminals, WebView/Gecko, desktop UA, PiP | [Terminals](terminal.md), [browser engines](browser-engines.md), [picture-in-picture](picture-in-picture.md) |
| Language latching, overlays, approval, task notifications | [Language](i18n.md), [overlays and notifications](overlay-and-notifications.md) |
| Source, pinned inputs, signing, release, and test evidence | [Build and release](build-and-release.md), [domain boundaries](agents/domain.md), [issue workflow](agents/issue-tracker.md), [triage vocabulary](agents/triage-labels.md) |

New focused guides record verification date, baseline, actual activation timing, and failure/verification limits. The three agent-workflow pages were newly written during F11 maintenance; they do not claim recovered historical originals or prove remote labels, permissions, or workflow configuration. A glossary is created when terminology actually requires it.

Current terminology: proot is default; proroot is explicitly selectable. Web ports are automatic by default; fallback memory is local. DNS auto is not global IPv4-first behavior. Production native model editors have been removed; model settings are in Web. PiP defaults off. One-click backup safely stops DSH/tasks before writing a password-free archive; external workspace contents need separate backup. Refer to the focused page and code for precise definitions.

## Reading historical material

The [changelog](../../CHANGELOG.md) preserves former defaults/performance observations and adds build190 migration information. DSHA v1.1.6 is not the upstream proroot version; the current vendor lock is v1.2.8. An old build's fallback, installation, certificate, package, or test count does not establish a later delivery's result.

Source distribution includes ordinary maintenance Markdown and necessary historical release explanations, including real fix1, build156, and old handoff originals. Their temporal attribution is unchanged. Referenced historical `app/build` receipts, local APKs, raw device logs/screenshots, and private audit attachments are not uploaded merely to repair navigation. They were local material at that time, not present repository downloads or evidence of rerunning the current build. Start maintenance here; old scripts do not authorize another audit APK or deletion of user data.

Publication checks distinguish current-guide links, available historical explanations, and undistributed local evidence. A recovered historical original is digest-checked before inclusion; missing receipts are not reconstructed. Lists below locate reports, without promising their entire original attachment sets.

## Documentation updates and reproducibility

The F11 closure work checked the actual distribution stage, rather than treating the developer machine's complete tree as a closed delivery. The following exact historical files are explicitly allowed. They prove their own time only and do not revive retired formatting, review, version, or acceptance rules.

| Genuine historical file | Distribution purpose |
|---|---|
| [build154 POLICY](../audits/build154/POLICY.md) | Constraints of the old audit; current rules remain in AGENTS and maintained topics |
| [rc2 architecture assessment](../audits/2026-09-27-rc2-full-architecture/final-assessment.md) | Build147 architecture/delivery scope, not this round's device receipt |
| [build155 architecture](../audits/build155/architecture-assessment.md), [remaining work](../releases/build155-remaining-work-20261003.md) | Recursive document dependencies of corresponding release/build156 assessments |
| [build156 architecture](../audits/build156/architecture-assessment.md), [remaining work](../releases/build156-remaining-work-20261003.md) | Original historical scope and unverified items |
| [Historical retention list](../audits/build154/history-document-sources.json), [ShellService provenance](../audits/build156/shellservice-source-provenance.json), [retained BUILD record](../audits/build156/source-docs-retained.json) | Three added explicit JSON metadata files, taking the allowed metadata set from six to nine, without opening all audit JSON |
| [Historical Termux README](../../tools/history/documents/tools/termux-jni/README.md) | Genuine 3547-byte original found on F:, matching retention-list SHA `c29a880a9d7014d58b125bd57e6f7a52a4bae4bda64e76dd22a2819382f521ca` |

A retention list does not make every listed historical object part of this source package. Execution ledgers, raw device images/logs, and similar material retain their own privacy/distribution boundaries. Unknown rights in provenance JSON do not become established rights because the JSON can be downloaded.

Five **navigation copies** were newly created during F11 so original-byte release pages' relative links work in GitHub and the source ZIP. Each is byte-identical to the stated canonical source. They are new navigation copies, not five rediscovered historical saved originals. Existing historical release originals were not edited or given a new interpretation of their relative paths. The fifth page was a necessary recursive target of the first copies.

| New navigation copy | Byte-for-byte source |
|---|---|
| [build156 architecture copy](../../tools/history/documents/docs/audits/build156/architecture-assessment.md) | [Canonical original](../audits/build156/architecture-assessment.md) |
| [build156 remaining-work copy](../../tools/history/documents/docs/releases/build156-remaining-work-20261003.md) | [Canonical original](../releases/build156-remaining-work-20261003.md) |
| [build155 remaining-work copy](../../tools/history/documents/docs/releases/build155-remaining-work-20261003.md) | [Canonical original](../releases/build155-remaining-work-20261003.md) |
| [build155 architecture copy](../../tools/history/documents/docs/audits/build155/architecture-assessment.md) | [Canonical original](../audits/build155/architecture-assessment.md) |
| [build155 release-page copy](../../tools/history/documents/docs/releases/v0.2.6-build155-20261003.md) | [Canonical original](../releases/v0.2.6-build155-20261003.md) |

Two unchanged pinned upstream originals contain eight **upstream-relative references**, not promises of local APK assets. Mobile README SHA `a1c401a59c4cc2b08dc0b3371c41e54692c552122059598f1c073dda9421ceae` references four images and AGENTS at commit `4e71eb67de7dac9d8b2572757c81e47aa32a32fb`. Termux license SHA `a8992ad867b1b4ea442693acb2e1ee829a814758e79a099bd8243a04ae383304` references two directories and a shared license at `6e2689f55295fa444be8ac8592c527c2c5ef3253`. The checker outputs their exact upstream URLs as `upstream-source-relative` warnings. `--strict-history` still fails. A changed source path, source digest, or literal target cannot reuse the classification; other missing targets remain errors. Neither upstream original nor APK assets were modified for this classification.

1. When changing defaults, settings, environment variables, authorization, or ports, inspect ConfigStore, the configuration entry, and the actual call chain. Document visible scope, next activation point, and migration.
2. Synchronize focused guides, the handbook, README, and changelog. Preserve historical attribution instead of overwriting old results with present state.
3. Target existence and inclusion in Git/the complete source ZIP are separate checks. New maintained pages must enter the final delivery list.
4. Platform API documentation describes platform contracts. Hidden APIs, OEM behavior, and actual permission require current code and explicit device evidence. Primary-source links are not upstream endorsement of this branch.
5. Record test subjects, failures/skips, incremental/cache reuse, and unexecuted scope. Separate source CI, host, browser, actual APK, and phone acceptance; device acceptance is paused for this work.
6. Run source, lock/descriptor, and license checks. One delivery owner verifies package/signature/attachment hashes. Private keys, user credentials, raw device logs, and screenshots are not documentation uploads.

## Historical explanations included with source

The following original-language documents are historical evidence, not the English operating path:

- [ROADMAP.md](../ROADMAP.md)
- [adb-flow-audit.md](../adb-flow-audit.md)
- [backup-upgrade-acceptance.md](../backup-upgrade-acceptance.md)
- [backup-upgrade-baseline.md](../backup-upgrade-baseline.md)
- [device-acceptance-2026-09-05.md](../device-acceptance-2026-09-05.md)
- [extract-startup-speed-2026-09-10.md](../extract-startup-speed-2026-09-10.md)
- [functional-audit-rc1.4.md](../functional-audit-rc1.4.md)
- [issue67-recovery.md](../issue67-recovery.md)
- [maintenance-rc1.4-verification.md](../maintenance-rc1.4-verification.md)
- [optimization-backup-verification.md](../optimization-backup-verification.md)
- [optimization-implementation.md](../optimization-implementation.md)
- [optimization-runtime-verification.md](../optimization-runtime-verification.md)
- [optimization-site-verification.md](../optimization-site-verification.md)
- [optimization-transfer-verification.md](../optimization-transfer-verification.md)
- [optimization-web-verification.md](../optimization-web-verification.md)
- [pid-stop-fix-2026-09-10.md](../pid-stop-fix-2026-09-10.md)
- [plugin-download-npm-fix-2026-09-05.md](../plugin-download-npm-fix-2026-09-05.md)
- [plugin-layout-install-fix-2026-09-05.md](../plugin-layout-install-fix-2026-09-05.md)
- [release-rc1.2-2026-09-06.md](../release-rc1.2-2026-09-06.md)
- [release-rc1.4-2026-09-07.md](../release-rc1.4-2026-09-07.md)
- [releases/v0.1.5-rc1-notes.md](../releases/v0.1.5-rc1-notes.md)
- [releases/v0.1.5-rc1.md](../releases/v0.1.5-rc1.md)
- [releases/v0.1.5-rc2-build129.md](../releases/v0.1.5-rc2-build129.md)
- [releases/v0.1.5-rc2-notes.md](../releases/v0.1.5-rc2-notes.md)
- [releases/v0.1.5-rc2.1-build130.md](../releases/v0.1.5-rc2.1-build130.md)
- [releases/v0.1.5-rc2.1-build131.md](../releases/v0.1.5-rc2.1-build131.md)
- [releases/v0.1.6-alpha1-build134.md](../releases/v0.1.6-alpha1-build134.md)
- [releases/v0.1.6-alpha1-build135.md](../releases/v0.1.6-alpha1-build135.md)
- [releases/v0.1.6-alpha2-build136.md](../releases/v0.1.6-alpha2-build136.md)
- [releases/v0.1.6-alpha2-build137.md](../releases/v0.1.6-alpha2-build137.md)
- [releases/v0.1.6-alpha2-build140.md](../releases/v0.1.6-alpha2-build140.md)
- [releases/v0.1.6-alpha2-build141.md](../releases/v0.1.6-alpha2-build141.md)
- [releases/v0.1.7-rc1-build146.md](../releases/v0.1.7-rc1-build146.md)
- [releases/v0.1.7-rc2-apk-size.md](../releases/v0.1.7-rc2-apk-size.md)
- [releases/v0.1.7-rc2-build147.md](../releases/v0.1.7-rc2-build147.md)
- [releases/v0.1.7-rc2-emergency-recovery.md](../releases/v0.1.7-rc2-emergency-recovery.md)
- [releases/v0.1.7-rc2-microphone.md](../releases/v0.1.7-rc2-microphone.md)
- [releases/v0.1.7-rc2-notes.md](../releases/v0.1.7-rc2-notes.md)
- [releases/v0.1.7-rc2-plugin-network-issues79-80.md](../releases/v0.1.7-rc2-plugin-network-issues79-80.md)
- [releases/v0.1.7-rc2-web-identity-recovery.md](../releases/v0.1.7-rc2-web-identity-recovery.md)
- [releases/v0.1.7-rc2-web-mobile-3.0.3.md](../releases/v0.1.7-rc2-web-mobile-3.0.3.md)
- [releases/v0.1.7-rc2.1-build148.md](../releases/v0.1.7-rc2.1-build148.md)
- [releases/v0.1.7-rc2.1-maintenance-20260930.md](../releases/v0.1.7-rc2.1-maintenance-20260930.md)
- [releases/v0.2.0-rc2-20260930.md](../releases/v0.2.0-rc2-20260930.md)
- [releases/v0.2.0-rc2-notes.md](../releases/v0.2.0-rc2-notes.md)
- [releases/v0.2.1-rc.2-build190-20261019.md](../releases/v0.2.1-rc.2-build190-20261019.md)
- [releases/v0.2.1-rc.2-build190-fix1-20261019.md](../releases/v0.2.1-rc.2-build190-fix1-20261019.md)
- [releases/v0.2.1-rc.2-build190-fix2-20261020.md](../releases/v0.2.1-rc.2-build190-fix2-20261020.md)
- [releases/v0.2.1-rc.2-build190-fix3-20261020.md](../releases/v0.2.1-rc.2-build190-fix3-20261020.md)
- [releases/v0.2.21-build170-20261009.md](../releases/v0.2.21-build170-20261009.md)
- [releases/v0.2.5-build154-20261003.md](../releases/v0.2.5-build154-20261003.md)
- [releases/v0.2.6-build155-20261003.md](../releases/v0.2.6-build155-20261003.md)
- [releases/v0.2.7-build156-20261003.md](../releases/v0.2.7-build156-20261003.md)
- [releases/v1.2.0-rc1.2.md](../releases/v1.2.0-rc1.2.md)
- [releases/v1.2.0-rc1.4.md](../releases/v1.2.0-rc1.4.md)
- [releases/v1.5-alpha.2.md](../releases/v1.5-alpha.2.md)
- [source-updates/20261019-build190-vscreen-port.md](../source-updates/20261019-build190-vscreen-port.md)
- [stability-acceptance.md](../stability-acceptance.md)
- [terminal-process-reaping-handoff.md](../terminal-process-reaping-handoff.md)
- [ui-layout-plan-rc1.4.md](../ui-layout-plan-rc1.4.md)
- [ui-layout-rc1.4-verification.md](../ui-layout-rc1.4-verification.md)
- [update-flow-rc1.4-verification.md](../update-flow-rc1.4-verification.md)
- [upgrade-dsh-0.1.5-alpha.2.md](../upgrade-dsh-0.1.5-alpha.2.md)

## Original bytes and readable views

The following historical files originally used CRLF. Their usual paths provide views with only line endings normalized to the current LF standard. Full original bytes are retained in tools/history under existing Git attributes; retired material is not deployed or discovered as tests. No acceptance facts were added. View and original hashes are recorded separately.

| Readable view | Original bytes | Original SHA-256 |
|---|---|---|
| [build190](../releases/v0.2.1-rc.2-build190-20261019.md) | [Original](../../tools/history/documents/docs/releases/v0.2.1-rc.2-build190-20261019.md) | `f172ce28b9cbc8933e8ae3bc7ad0f01f39441e3c0e9eecbbcf955b06ea432d37` |
| [build190 fix1](../releases/v0.2.1-rc.2-build190-fix1-20261019.md) | [Original](../../tools/history/documents/docs/releases/v0.2.1-rc.2-build190-fix1-20261019.md) | `d34ca245134ce842f404c52fe52060c9255bd31d4ecee2bc7c5966a0017c3c0c` |
| [build156](../releases/v0.2.7-build156-20261003.md) | [Original](../../tools/history/documents/docs/releases/v0.2.7-build156-20261003.md) | `8cc70cb1720736e0f9c7427b6b5399155b1983d4bfe51d755ece02dc0196cd55` |
| [build190 virtual-screen/port source update](../source-updates/20261019-build190-vscreen-port.md) | [Original](../../tools/history/documents/docs/source-updates/20261019-build190-vscreen-port.md) | `7a37fa7c1155553d0fa767e629a5a437ccf33fcabb9d0e41b976fe7841890bc4` |
