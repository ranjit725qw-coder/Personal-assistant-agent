# Mobile Harness v1.0.4 upstream sync

Source: `techjarves/Mobile-Harness`  
Target: `ranjit725qw-coder/Personal-assistant-agent`  
Common baseline: Mobile Harness v1.0.3 (`c02b663` era)  
Reviewed upstream through: `15177fc` (v1.0.4)

## Summary

The target app forked before the upstream v1.0.4 development cycle and now has substantial local features: voice input/readout, custom application ID, OTA release/update handling, resumable downloads, multi-key 429 failover, and terminal/file-read reliability fixes. A blind replacement of upstream files would remove or regress these custom features.

This sync therefore uses staged, conflict-aware ports rather than copying the upstream tree wholesale.

## Upstream changes missing from the target

### P0 — runtime and task safety

- Keep active AI tasks and terminal commands running when navigating back.
- Show running-task state on project cards.
- Permit read-only browsing of other project chat histories while a task is active.
- Reject incompatible x86/x86_64 environments that merely advertise translated ARM64 support.
- Improve Claude subscription-token handling and prevent stale API-key variables from shadowing OAuth tokens.
- Improve runtime/toolchain diagnostics and setup progress.

### P1 — agents and providers

- DeepSeek Harness runtime, route mapping, workspace checkpoints, shutdown handling, and tests.
- Antigravity runtime with authentication and PTY support.
- Primary-agent selection during onboarding.
- Cross-agent provider setup.
- NVIDIA NIM provider preset and fixed OpenAI-compatible protocol routing.
- Public model discovery without requiring an API key where supported.
- OpenCode compatibility and custom-provider routing fixes.
- Agent installation download tracking and redesigned agent-update UI.

### P1 — setup and storage

- Runtime filesystem compatibility links and legacy DeepSeek migration.
- Optional development-stack removal (Python, Android, C/C++, PHP) without deleting projects.
- More accurate setup progress and toolchain validation.

### P2 — release metadata

- Upstream v1.0.4 README and update-manifest refresh.
- These values must not be copied directly because this repository uses its own package ID, OTA channel, signing pipeline, and release assets.

## Target-only behavior that must be preserved

- Voice input and text-to-speech readout.
- `com.ranjit.personalassistant` application ID.
- Repository-owned OTA manifest and signed release workflow.
- Online-edition OTA builds and resumable HTTP Range downloads.
- Pre-download install permission flow and staged installation progress.
- Multi-key provider pool with automatic 429 failover.
- Bounded terminal output, stale file-read protection, and partial-download cleanup.

## Conflict map

The highest-risk merge files are:

- `ui/MainViewModel.kt`
- `ui/PocketDevApp.kt`
- `ui/SettingsScreenModern.kt`
- `runtime/RuntimeInstaller.kt`
- `runtime/ClaudeRuntimeBridge.kt`
- `data/AppPreferences.kt`
- `network/ProviderApiClient.kt`
- `model/Models.kt`

These files contain both upstream v1.0.4 work and target-only features, so they require manual semantic merges plus tests.

## Staged merge plan

1. **Runtime compatibility and Claude auth safety**
   - Add the ARM64 compatibility predicate and tests.
   - Wire the predicate into setup/bootstrap in the next code port.
   - Port Claude subscription-token environment isolation.

2. **Background task navigation**
   - Merge active-task persistence, project status indicators, and read-only history into the voice-enabled UI.

3. **Agent/runtime expansion**
   - Port DeepSeek Harness and Antigravity runtime components, provider models, preferences, onboarding, and tests as one coherent unit.

4. **Toolchain lifecycle**
   - Port removable development stacks while retaining resumable downloads and current OTA behavior.

5. **Release validation**
   - Run unit tests and online-release build.
   - Keep this repository's own manifest URL, application ID, signing, and voice features.

## Acceptance checks

- Voice input/readout remains available.
- Existing projects and chat history remain intact.
- Active tasks survive back navigation.
- Claude subscription tokens launch without API-key shadowing.
- DeepSeek Harness and Antigravity install and launch successfully.
- Multi-key 429 failover still works.
- OTA downloads still resume after interruption.
- `testOnlineReleaseUnitTest` and `assembleOnlineRelease` pass.
