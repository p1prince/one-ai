# AI providers

ONE AI talks to AI services through one path: `AiOrchestrator` -> `ActiveAiProvider` -> the adapter registered for the
active profile's `kind`. Chat, commands and the Floating Bubble all use it, so switching the active profile in Settings
changes all three on the next request.

| Provider | kind | Auth | Adapter | Endpoint rule |
|---|---|---|---|---|
| OpenAI | `openai` | API key | OpenAI-compatible (`AiProviderClient`) | HTTPS |
| ChatGPT Account | `chatgpt_account` | Browser sign-in (OAuth) | `ChatGptAccountProvider` | Fixed, not editable |
| Google Gemini | `gemini` | API key (`x-goog-api-key` header) | `GeminiProvider` | HTTPS |
| Anthropic Claude | `claude` | API key (`x-api-key`) | `AnthropicProvider` | HTTPS |
| DeepSeek, Groq, OpenRouter | `deepseek`, `groq`, `openrouter` | API key | OpenAI-compatible | HTTPS |
| Ollama (local) | `ollama` | Optional key | `OllamaProvider` | HTTPS, or plain HTTP to this device only |
| Custom Provider | `custom` (existing kind, unchanged) | API key | OpenAI-compatible | HTTPS |

A provider is offered in Settings only if its adapter is registered (`ProviderCatalog.available`).

## Credentials
- API keys are stored per profile in the encrypted vault (`ProfileSecrets`). A profile never reads another profile's key.
- The ChatGPT session is stored separately (own Keystore key, `KeystoreChatGptSessionStorage`), never in a profile or the
  API-key vault. OpenAI API-key auth and ChatGPT-account auth never share tokens.
- Secrets are never logged; error text is scrubbed of the key/token used for the request.

## ChatGPT account (experimental)
Recovered from the working Orbit-style implementation in `p1prince/one-ai-android`: authorization code + PKCE (S256),
random `state` checked in constant time, loopback receiver on 127.0.0.1 ports 1455/1457, RS256 ID-token verification,
serialized refresh with rotated refresh token, account id header, one forced refresh and retry after a 401, then
`SESSION_EXPIRED`. It uses the Codex client registration and the ChatGPT-hosted Responses backend, which is not a
documented public API and can change or be restricted without notice. The default model name `gpt-6-luna` is carried over
and must be confirmed on a device.

## Tests
All provider tests use the `AiHttp` mock transport; no real key or network is needed.
