# Direct-API Codex provider (design / decision pending)

Goal: replace the per-step `codex exec` **process spawn** with a persistent
**direct HTTP** call, removing ~1–3s/step of CLI startup and enabling
**prompt caching**, while keeping the existing `ArbigentAiProvider` boundary so
OpenAI/Codex/Gemini paths are untouched.

This is a **design doc pending approval** — see the ToS/security note before
implementing the subscription path.

## Why

- Research (OSWorld-Human): LLM calls are 75–94% of task time. The codex-CLI path
  also pays a fresh-process spawn every step and cannot reuse a cross-call prompt
  cache. A direct HTTP provider removes the spawn and lets the stable prompt prefix
  (system prompt + action list + output contract) be cached.
- Stateless `off` is already the default; a direct provider keeps that (one
  self-contained request per step) but adds caching + drops spawn.

## Two options

### Option A — Reuse the ChatGPT subscription (what OpenClaw / simonw do)

The local Codex CLI, when logged in with a ChatGPT account, holds an OAuth
access token + refresh token + account id. Tools like OpenClaw and
`simonw/llm-openai-via-codex` call the model by POSTing directly to the ChatGPT
backend with that token — **no `codex` process, no pay-per-token API key**.

- Endpoint: `POST https://chatgpt.com/backend-api/codex/responses` (Responses-API
  wire shape; `chatgpt_base_url` default per Codex config docs).
- Auth: `Authorization: Bearer <access_token>` + `chatgpt-account-id: <account_id>`
  read at runtime from the Codex auth file (the same file the CLI already uses).
- Refresh: `POST https://auth.openai.com/oauth/token` (`grant_type=refresh_token`,
  public Codex client id) when the access token nears expiry (~10-day lifetime).
- Supports image input and strict JSON-schema output (under `text.format`); the
  backend streams SSE (`stream: true`, `store: false`), so we accumulate the
  stream and validate the final JSON — same action contract as today.
- Caching: set a stable `prompt_cache_key`.

Pros: reuses the existing ChatGPT subscription (no extra API spend), same models
the user already has via Codex. Cons / **caveats**:

- **Undocumented endpoint** — can change without notice.
- **ToS gray area** for non-Codex programmatic use; OpenAI recommends API keys /
  enterprise Codex access tokens for automation.
- **Refresh-token rotation**: if our app and the Codex CLI refresh the same token,
  one can log the other out. Mitigation: only read (let the CLI own refresh) and
  re-read on 401, or run a separate login. Must not corrupt the user's CLI auth.
- The public `api.openai.com/v1/responses` rejects ChatGPT-plan tokens
  (`Missing scopes: api.responses.write`) — must use the `/backend-api/codex` path.

> Security note: investigating this required reading the local Codex auth file.
> No secrets were printed or exfiltrated. Any implementation must read those
> credentials only at runtime on the user's machine (exactly as the CLI does),
> never log/transmit them, and must not break the CLI's own session. Proceed only
> with explicit user approval given the ToS considerations.

### Option B — Official OpenAI Responses API with a key (documented, safe)

`POST https://api.openai.com/v1/responses` with `Authorization: Bearer <API_KEY>`,
same Responses body (image via `input_image`, JSON schema via `text.format`),
`prompt_cache_key` for caching, non-streaming supported. Stable and supported;
costs per-token. Lowest-latency knobs: faster model tier, `reasoning.effort=low`,
`service_tier=priority` if available.

## Plan (either option) — keep the provider boundary

- New `OpenAiResponsesAiProvider` (or extend `OpenAICompatibleAiProvider`) producing
  an `ArbigentAi` that does one HTTP Responses call per decision, reusing the
  existing `AgentActionJsonParser` and the same decision/output schema.
- Transport selected by config: `--ai-type=codex-direct` (Option A, reads Codex
  auth) or the existing OpenAI options + `--openai-use-responses` (Option B).
- Prompt: reorder to a **byte-stable prefix** (SYSTEM_PROMPTS + AVAILABLE_ACTIONS +
  AVAILABLE_MCP_TOOLS + OUTPUT_CONTRACT) followed by the variable UI state + image,
  and set `prompt_cache_key` — so the static prefix is cached across steps.
- Keep `codex` (CLI) provider as-is for users who want it.

## Expected impact

- Removes ~1–3s/step spawn; prefix caching cuts image+prompt prefill on repeat
  steps. Combined with the existing `off` default and the image cap, targets
  ~10–15s/step on the current model, and is the seam to later swap in a faster
  model (the real lever) without touching the agent loop.

## Rejected alternatives

- Spawn `codex exec` but keep a warm process / daemon: brittle, still no HTTP
  caching control, and Codex CLI is not designed as a server.
</content>
