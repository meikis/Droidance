# Rules and runtime configuration

Droidance keeps Agent behavior in `app/src/main/assets/rules/` instead of Kotlin. Kotlin owns orchestration, protocol validation, device execution, persistence and factual records; the Markdown and YAML files own prompts, operating policy and tunable limits. This page explains where each file reaches the model, so a change can be placed and measured deliberately.

## How rule text reaches the model

Every model round sends a stable prefix (system prompt and tool schemas) followed by the conversation and a per-round tail. Rule text costs tokens differently by layer:

| Layer | Sent | Files and sections |
|---|---|---|
| System prompt | Every round, cached as prefix | `agent_core.md` (`operator_behavior`, `public_metadata`, `output_language`, `reasoning_control` when the Provider accepts explicit efforts), `device_tools.md` (`android_read_description`, `screen_act_description`, `device_inventory_contract`), `completion_recovery.md` (`batch_contract`, `contract`, `delivery_check`), `workspace.md` (`collection_contract`), `tool_context.md` (`contract`), `conversation_title.md` (`policy`) |
| Tool schemas | Every round, cached as prefix | `tool_context.md` (`schema_context_policy`: tool and parameter descriptions; `helpOnlyParameterTools` sends only types and constraints for rarely used tools, whose full documentation `workspace` help returns), parameter sections in `agent_core.md` and `completion_recovery.md`, tool descriptions in `device_tools.md` and `automation_tools.md` |
| Per-round tail | Every round, not cached | Runtime facts plus short sections such as `model_context.md` and `observation_recovery.md#navigation_facts` |
| Situational | Only when the situation occurs | `observation_recovery.md` (recovery and replanning), `completion_recovery.md` (review and repair), `utility_prompts.md` (protocol repair, compaction, repeated tools), `context_compaction.md`, `stopped_task_reply.md`, `public_fallbacks.md` |
| Feature | Only for that feature | `memory_policy.md`, `skills.md`, `automation_prompts.md`, `automation_tools.md`, `program.md` |
| Runtime configuration | Read by Kotlin, never sent | `harness_policy.yaml`, `harness_prompts.yaml`, `tool_contracts.yaml` |

Each round records `system_prompt_chars` and `tool_schema_chars` (with a per-tool breakdown in `tool_schema_chars_by_name`) in its model round metadata.

## Writing rules

- Put guidance where the decision happens. A sentence in a parameter description is followed more reliably than a paragraph in the system prompt; device runs showed a trailing-wait habit disappear only after the `actions[].ms` description stated it.
- Prefer one short, general rule over several situational ones. Never name a specific App, page or business case; the Agent must stay general.
- Keep facts the runtime can derive out of model instructions. Validate them in Kotlin and return a concise, repairable error instead.
- Keep the stable prefix stable within a task. Changing the system prompt or tool set mid-task discards the Provider's prefix cache.
- Agent rules and Skill Markdown are written in standard English. User-facing text belongs in Android string resources.

## Tunable configuration

`harness_policy.yaml` groups its values by subsystem. The following groups are the ones a deployment is most likely to tune; the rest are internal limits whose defaults are validated by unit tests.

| Group | Typical reasons to change |
|---|---|
| `runtime_thresholds.llm_*` | Slow or unreliable Providers: first-event and stream-idle deadlines, retry count and backoff, output token budgets |
| `runtime_thresholds.tool_execution_timeout_ms`, `app_launch_settle_timeout_ms`, `route_entry_settle_timeout_ms` | Slow devices or heavy Apps |
| `runtime_thresholds.app_first_open_launch_mode` | Whether an App's first open in a task starts from its entry (`reset_task`), resumes its last page (`resume`) or cold-starts it (`fresh`) |
| `runtime_thresholds.context_capture_history_max_images` | Trade model latency (every retained screenshot is resent each round) against how many earlier screens stay attached |
| `screen_action_batch` | Allowed screen primitives, wait/capture limits and `max_drag_repeat` per drag |
| `vision_frame` | Screenshot size and JPEG quality sent to the model |
| `memory_context` | How much memory and Skill catalog text enters a task |

Unit tests check relations between values (for example, the stream-idle deadline cannot exceed the round timeout), not the values themselves, so tuning a value does not require editing tests.

## Model capabilities

`app/src/main/assets/model_capabilities/reasoning.json` declares which reasoning efforts a model accepts:

- `models[].ids` matches exact model ids; `models[].prefixes` matches id prefixes.
- `gateways[].hosts` matches the Provider base URL host. `parameter` selects the Chat Completions dialect (`REASONING_EFFORT` for a top-level `reasoning_effort`, `REASONING_OBJECT` for `reasoning.effort`); Responses requests always use `reasoning.effort`. `efforts` applies to models on that host without their own profile, which covers aliases such as coding-plan model names.

A Provider that rejects an effort is retried once with its default and remembered for 24 hours, so an over-broad entry degrades to the previous behavior instead of failing a task. The rejection is recognized from the error's `param`, a reasoning-specific code, or an invalid-parameter message that names the reasoning field; verify a new gateway's error format before adding broad efforts. Add a source link for every entry.

`app/src/main/assets/model_capabilities/request_fields.json` adds top-level fields to a gateway's Responses requests, such as Volces Ark's session cache `{"caching": {"type": "enabled"}}`. An entry may narrow its hosts with `path_prefixes`; Ark's coding plan endpoint (`/api/coding/v3`) rejects caching and does not store responses, so only `/api/v3` receives it. A field never replaces a value already in the request. A request rejection whose message names an added field disables the fields for that Provider scope and retries once without them.

To add an OpenAI-compatible gateway such as OpenRouter or OpenCode Zen (`https://opencode.ai/zen/v1`), first confirm its reasoning parameter and cache behavior, then add a `gateways` entry for its host in these files. No Kotlin change is needed.

Each model round records `responses_continuation` (whether `previous_response_id` was sent), `responses_input_items`, `responses_skipped_retained_images` and `responses_extra_fields`, alongside the Provider-reported cached input tokens, so cache behavior can be checked from a device run. A continuation sends only new input: a historical image the stored chain already holds is not attached again, and the chain's image record resets whenever a response is not retained.
