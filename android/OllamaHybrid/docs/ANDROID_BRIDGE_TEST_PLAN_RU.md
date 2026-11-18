# Hybrid Android Bridge — тест-план

## Static checks
- Kotlin Bridge contracts compile with Kotlin/JVM compiler.
- AndroidManifest and service XML parse.
- Duplicate tool IDs rejected.
- Missing capability blocks execution.
- READ_ONLY may run without approval.
- REVERSIBLE/SENSITIVE/DESTRUCTIVE require host-created ApprovalGrant bound to request ID and tool ID.
- Root probe verifies UID 0; UID 2000 is not Root.
- Model cannot supply arbitrary shell command.
- Specs distinguish implemented scaffold from planned work.

## Unit test cases
1. Duplicate tool ID rejected.
2. Unavailable transport is never selected.
3. Highest-priority supported transport selected.
4. Missing capability blocks execution.
5. Read-only tool policy.
6. Risky action requires approval.
7. Approval for a different request/tool is rejected.
8. Root UID classification.
9. Root timeout/denial/missing binary produce non-success.
10. Model-supplied arbitrary shell is impossible.

## Device matrix
- Stock Android, no Root/Shizuku: Standard + manually enabled Accessibility.
- Shizuku ADB: only capabilities confirmed for shell UID and OS.
- Shizuku Root/Sui: confirm UID 0 and allowlisted tools.
- Direct Magisk Root: Root Manager prompt and UID 0 verification.
- Root denied/missing: no crash; Standard remains.
- Shizuku Binder death: in-flight privileged operation fails safely and capabilities refresh.
- Accessibility revoked: UI tools disabled; Native continues.
- MediaProjection denied: screenshot fails safely.
- Reboot: all capabilities re-detected, no stale grant assumptions.

## Release acceptance
- assembleDebug and assembleRelease succeed on configured Android build host.
- APK installs and launches on physical device.
- Permission Center reports verified state.
- Root and ADB identities distinguished.
- No arbitrary model-controlled shell.
- Audit includes tool, transport, approval and result.
- Recovery Core remains outside editable workspace.
- Previous stable APK remains in GitHub Releases.

## Current test status
No Gradle wrapper, Gradle executable or Android SDK is available in the current environment. Real Android build, APK install, emulator and Root/Shizuku hardware tests are not claimed. Only static validation and isolated Bridge Kotlin/JVM smoke compilation/tests are reported.
