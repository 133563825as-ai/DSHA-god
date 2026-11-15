# Root-01 revocation delivery implementation plan

> For agentic workers: execute inline using superpowers:executing-plans. The user explicitly authorized continuous implementation and Draft PR delivery without reconfirmation.

Goal: deliver reproducible Root-01 evidence and narrow fixes to DSHA-god integration/sprout.
Architecture: retain process-local generation gate, separately assess actual stdin dispatch and durable revocation. Never relabel authorization claim as proven byte handover.
Tech Stack: Java 17, Android, JUnit 4, Robolectric, Gradle.
Spec: user attachment f18e3123-8a5a-4753-9af6-615780b7e8ad; Root-01 plus supplied review.

## Global constraints
- Base 4027e4c8a84fa25b09d9b3d7e6b8f833b898dd1e, target integration/sprout on 133563825as-ai/DSHA-god.
- Preserve sprout-root-audit untouched; do not merge, release, publish APKs or read private device data.
- Prior reports and APK are historical evidence, not current PASS.
- Unresolved P0 means Draft only. No claim of full acceptance.

## Review focus
- Claim before first byte: revoke while write is suspended; record actual byte count.
- Corrupted permission true/1: reject rather than coerce.
- External Root off/on ABA: retain additional external dispatchCurrent guard.
- Persistence apply failures/process death: never infer durable success.
- Partial output and old callbacks: retain UNKNOWN, no automatic replay.

## Task 1: freeze and reproduce
- [x] Fetch target base; isolate worktree and copy explicit existing Root-01 files only.
- [x] Re-run existing 86 tests and record source digests.
- [x] Add ConfigStore true/1 negative tests; observe failure before fix.
- [x] Add external helper-start ABA tests; observe failure before fix.

## Task 2: narrow fixes
Files: core/ConfigStore.java, RootShell.java and existing Root-01 tests.
- [x] Require Boolean.TRUE for Root authorization, keeping other preferences unchanged.
- [x] Capture Root permit at each external request entry and consume at handover alongside external guard.
- [x] Run positive/negative unit and real RootShell simulation tests; no real su.
- [x] Assess atomic dispatch and durable storage separately. If unresolved, document P0 and Draft.

## Task 3: evidence and delivery
- [x] Add complete A/B/C and INV matrix with PASS/FAIL/BLOCKED/NOT_RUN per evidence layer.
- [x] Build candidate if fixed inputs available; hash artifact; old device APK never substitutes.
- [x] Run source/format/manifest/changelog/link checks, classify any baseline failure.
- [x] Independent full-diff review: REQUEST_CHANGES, two P0 remain; explicit staged-file credential/unrelated-diff check completed.
- [ ] Commit, push independent branch, create Draft or Ready only as evidence permits, verify base/head/URL.

Ruling: supplied user instructions override generic main-branch and plan reconfirmation examples. Native app worktree creation targets the outer empty repository, so nested DSHA-god uses git worktree fallback at a sibling path.

Checkpoint: source commit 0cf73994b108f9f8491652c8e1012b7fb3fbbe14; 161 final tests, six RED negatives, 275 repeat executions, clean-checkout 11 gate tests, unsigned APK build. Two P0 acceptance blockers remain; no local Android device. Final evidence audit completed: REQUEST_CHANGES, no new issues in the two narrow fixes. Draft PR delivery pending.
