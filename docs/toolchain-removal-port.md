# Optional toolchain removal port

This PR adds the conflict-free safety and ownership foundation for removing optional development stacks.

## Included

- Explicit package/path ownership plans for Python, Android, C/C++, and PHP.
- Protection for the core Web stack because Node.js, npm, Git, and the coding-agent runtime depend on it.
- Blocking rules while another install/removal, AI task, or project-terminal command is active.
- Rootfs-relative path validation that rejects absolute paths and traversal segments.
- Package-name validation and argument lists instead of interpolated shell commands.
- Unit tests for protected core tools, concurrency guards, path safety, and optional-stack plans.

## Preservation

No installed toolchain is removed by this PR. No voice, OTA, signing, application ID, provider, navigation, or multi-key failover files are changed.

## Follow-up integration

The RuntimeInstaller executor and Settings confirmation UI can consume these validated plans in a focused follow-up after the open foundation PRs are merged.
