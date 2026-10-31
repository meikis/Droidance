# Droidance Android-use blueprint

This document describes the current product and architecture. Contribution and
validation guidance is in [CONTRIBUTING.md](../CONTRIBUTING.md).
Implementation details belong to the owning source and the focused guides linked
below. Historical reports do not define current runtime behavior or establish current test results.

## Product and invariants

Droidance is a general mobile-use Agent running on an Android device. The same
Agent chooses direct conversation, Android capabilities, Shell, JavaScript,
Skills or visual interaction. There is no separate intent classifier, router,
planner, evidence summarizer or business-success judge.

Kotlin owns orchestration, protocol validation, device execution, persistence and
observed facts. App-specific strategies, task conditions and reusable methods
belong in English Markdown rules and Skills. User-facing interface text belongs
in localized Android resources; see [LANGUAGE_POLICY.md](LANGUAGE_POLICY.md).

GUI perception uses clean screenshots. Cropping magnifies details; it does not
replace the current execution frame. No AccessibilityService, UI hierarchy or
local OCR/detector pipeline supplies the Agent's visual decisions. External page
content is untrusted data and cannot expand the user's authorization.

## Module and state ownership

| Module | Responsibility |
| --- | --- |
| `app` | Android host, UI, dependency assembly, settings and runtime services |
| `core:agent` | Single decision loop, lifecycle, tool contracts, context and event interfaces |
| `core:model` | Model abstraction, provider sessions, transports and stream protocol |
| `core:device` | Sidecar, capture, input, Android capabilities and isolated script execution |
| `core:knowledge` | Skills, profile/episode memory and document storage |
| `core:persistence` | Room implementations of durable event and knowledge stores |

`core:agent` depends on `core:model`. Device and knowledge implementations depend
on Agent contracts; persistence depends on Agent and knowledge contracts. The
actual allowed dependency graph and pure-JVM boundaries are checked by
`verifyArchitecture`. The rationale remains in [ADR 0001](adr/0001-open-source-architecture-decisions.md)
and [ADR 0002](adr/0002-task-lifetime-and-contribution-checks.md).

EventStore is the durable source for Thread, Turn, model round, tool call,
execution attempt, user intervention and terminal facts. UI and notification
consumers project those facts; they do not infer lifecycle state from rendered
text. Provider continuation handles are transport acceleration, not the source
of conversation history. Original receipts and immutable attachments remain
retrievable after context compaction.

## Execution binding

Only **Standard virtual-space execution** is supported. Wireless debugging
pairing starts a resident shell-identity Sidecar. GUI capture and input use the
same frozen virtual display and backend session. The virtual space uses the
installed applications and their account data; it is not a separate data copy.

Device preflight freezes execution identity and verifies capability readiness
before dispatch. Losing the binding fails closed. It never redirects a visual
action to display zero or another backend. Ultra/Root and main-screen execution
identifiers remain only where needed to read historical events and reject
unsupported new execution.

General Shell commands use the frozen Standard backend. Shell is device-wide;
a virtual-display binding does not make arbitrary commands display-scoped.
Permissions, resource budgets, cancellation, redaction, output limits and
re-observation boundaries also apply to managed JavaScript calls through
ToolExecutor. The app does not claim to undo physical effects or replay an
uncertain mutation safely.

Pairing trust and live ADB transport are separate. Ordinary disconnections do
not erase pairing keys. A resident Binder Sidecar can remain usable after ADB
transport disconnects; rebuilding a lost Sidecar requires its startup channel.
The permission and distribution boundaries are documented in
[PERMISSIONS_AND_SECURITY.md](PERMISSIONS_AND_SECURITY.md) and
[NATIVE_BINARIES.md](NATIVE_BINARIES.md).

## Tools, context and completion

Android tools expose reads, native actions, screen actions and Shell. Workspace,
program, memory, Skills, automation, history and completion tools share the same
Agent loop. Tool profiles expose available capabilities; they do not classify
business intent. Static contracts, schemas and general runtime policies live in
`app/src/main/assets/rules/`; ToolCatalog remains the source of tool membership.

The Agent chooses whether notes, task memos, working files, source retrieval or
batches help the request. These are optional working state, not prerequisites
for every action or completion. Context compaction retains original facts and
uses configured budgets.

A batch executes bounded actions in order. It retains completed/attempted
prefixes, unknown effects, unexecuted suffixes and available intermediate
screenshots. New visual targets still require current observation. Program
execution crosses the same device checks and returns to the same Agent at
visual or unknown-effect boundaries.

Normal completion is direct: candidate confirmation, citations, structured
coverage and attachments are optional. Supplied references, indices, versions
and disclosure constraints are validated. Omitting them never manufactures
proof of success. Public answers must honestly describe meaningful unfinished
work. The explicitly configured same-Agent review path remains separate from
ordinary completion. See [EXPERIENCE_EXECUTION_PROTOCOL.md](EXPERIENCE_EXECUTION_PROTOCOL.md).

A delivered action, successful script exit, stored assessment or `done` event
records implementation facts. The Agent checks the actual requested outcome;
external acceptance separately inspects original evidence.

## Memory and Skills

Working context, task episodes, reusable operation experience, profile memory
and Skills have distinct ownership. Historical experience does not establish
current page state. Retrieval is bounded and methods must be rechecked against
current observations before reuse. Experience projection and storage do not
introduce another model request or independently certify semantic success.

Skills use `SKILL.md` with optional `scripts/**/*.js` and `references/**/*.md`.
Drafts, exact revisions, explicit activation, recorded demonstrations,
checkpointed effects and assessment provenance follow [SKILLS.md](SKILLS.md).
Legacy `SKILL.yaml`/`SCRIPT.json` execution is retired. Script success does not
publish a draft or complete the user's task. The optional
[Android platform reference Skill](../skills/android-platform-reference/SKILL.md)
is imported explicitly rather than seeded into every installation.

## Scheduling and intervention

Scheduled tasks, notification signals and App takeover create ordinary Turns
through the existing runtime. Results stay in the app and local notifications. The current
single virtual display serializes device interaction. Scheduling rules and
clock ownership are documented in [SCHEDULED_TASKS.md](SCHEDULED_TASKS.md).

Manual takeover is a cooperative pause with explicit resume. The retained viewer
allows the user to inspect and operate the same virtual display. Resuming uses
fresh state rather than blindly continuing old coordinates. Cancellation and
process interruption retain uncertainty about effects and close durable facts
without silently restarting physical work.

## Validation and distribution

Choose local checks by the affected behavior, following [CONTRIBUTING.md](../CONTRIBUTING.md). `scripts/verify.sh` provides the complete
offline check set; platform and release modes serve their respective tasks.

Device runs bind the installed APK hash, serial, exact Turn and retained export.
An observed terminal event is distinct from semantic success. Code/tests,
installed-device behavior and real-model business outcomes are reported
separately. Review exact-turn observations against each task’s requirements.

Release packaging follows [GITHUB_RELEASE_WORKFLOW.md](GITHUB_RELEASE_WORKFLOW.md)
and [VERSIONING_POLICY.md](release/VERSIONING_POLICY.md). Source and artifact
notices follow [THIRD_PARTY.md](THIRD_PARTY.md), [NOTICE](../NOTICE) and
[LICENSE](../LICENSE).
