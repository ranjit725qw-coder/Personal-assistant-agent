# Antigravity agent port

This PR adds the conflict-free protocol and authentication parsing foundation for the upstream Antigravity integration.

## Included

- Stream-JSON parsing for initialization, assistant text, tools, and results.
- Stable command construction for new and resumed conversations.
- Workspace prompts that keep generated files inside the mounted project.
- Wrapped Google PKCE URL extraction without capturing terminal labels.
- Unit coverage for event parsing, command flags, OAuth URLs, and workspace confinement.

## Security boundaries

- OAuth credentials are not parsed, copied, or logged.
- This layer only extracts the public authorization URL shown by the official CLI.
- Permission skipping applies inside the app's existing private PRoot project sandbox.

## Follow-up integration

The downloadable Antigravity CLI bundle, PTY-backed official OAuth controller, runtime bridge, agent selector, and account UI should be integrated together so the app never exposes an unavailable agent.
