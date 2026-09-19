# DeepSeek Harness port

This PR adds the conflict-free routing and output-protocol foundation for the upstream DeepSeek Harness integration.

## Included

- Native DeepSeek route using `DEEPSEEK_API_KEY`.
- Anthropic-compatible custom routes using a single in-memory `MH_DSH_API_KEY` environment variable.
- Deterministic DSH settings rendering without embedding secrets in YAML.
- Explicit rejection of Claude subscription login, which remains handled by Claude Code.
- Classification of DSH reasoning, diagnostics, and final-answer output.
- Unit tests for routing, secret isolation, settings, and output parsing.

## Preserved

No voice, OTA, signing, application ID, project-navigation, or multi-key failover code is changed.

## Follow-up integration

The Android process bridge, downloadable DSH bundle, agent selector, and onboarding UI should be added together in the next DeepSeek integration stage so the app never exposes an agent that is not installable and runnable.
