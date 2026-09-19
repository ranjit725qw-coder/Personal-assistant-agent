# NVIDIA NIM and provider validation port

This PR adds the conflict-free provider compatibility foundation for NVIDIA NIM and other OpenAI-compatible gateways.

## Included

- NVIDIA NIM preset with the official `https://integrate.api.nvidia.com/v1` endpoint.
- Default coding model: `qwen/qwen2.5-coder-32b-instruct`.
- Normalized model-list and chat-completions endpoints.
- Bearer-token request policy that rejects blank keys.
- A 45-second validation read timeout for provider cold starts.
- Actionable validation categories for rejected keys, endpoint errors, rate limits, and provider failures.
- Redaction of Bearer tokens and `sk-...` keys from provider error text.
- Unit tests for endpoints, model defaults, timeouts, validation errors, and secret redaction.

## Preservation

No voice, OTA, signing, application ID, navigation, or existing multi-key failover code is changed.

## Follow-up integration

After the provider foundations are merged, NVIDIA NIM can be exposed in the provider selector and routed through the format gateway without duplicating endpoint or validation logic.
