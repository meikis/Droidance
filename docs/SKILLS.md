# Android Skills

Skills run through the existing conversation Agent. The authoring workflow is in
`app/src/main/assets/rules/skills.md`; Kotlin owns package storage, exact revisions,
execution receipts and evidence references, not application-specific decisions.

## Learning during a task

The same task Agent can save a useful discovery immediately with
`experience(op="save", update=...)`, without waiting for `done` or adding a
reflection Turn. This uses the same parameterized method format as completion
proposals. Writes return a committed receipt and the new exact version reference;
an interrupted task does not discard an already committed discovery.

After expanding a relevant `experience:id:revision`, `op="use"` records actual
adoption and returns `use_id`. Reading alone does not count as adoption.
`op="assess"` binds the task Agent's succeeded/failed/unknown assessment to that
use and its original version. Success and failure require original observations
obtained after adoption. An unassessed adoption stays unsettled. These are scoped
Agent assessments, not host judgments or guarantees of future success.

Corrections replace the same method id using its exact current revision.
`source_use_id` preserves the relevant adoption; `source_quote` must occur in a
current user message or steer. A human-only correction remains unknown until
executed. Early success promotion requires an assessed use of the unchanged
current method; editing a route cannot inherit the old route's success. Conflicts
require rereading, and disabled/deleted methods cannot be silently restored.
Expanded methods include bounded recent uses, each with its own version reference.
Deletion clears method history and use details. Storage upgrade 3 to 4 adds the
use ledger while preserving existing methods and user memories.

Learning writes may accompany independent normal calls. No extra planner,
classifier, reflection model, or judge is introduced.

## Package

A package is UTF-8 `SKILL.md` plus optional `scripts/**/*.js` and
`references/**/*.md`. Import accepts Markdown or ZIP, and export produces ZIP.
The host rejects duplicate/traversing paths, malformed YAML, unsupported files and
packages over the configured limits (48 files, 256 KiB per file, 2 MiB total).
Legacy `SCRIPT.json`/`SKILL.yaml` packages are not executable under this contract.

```yaml
---
name: example-workflow
version: 1
description: Describe when this method applies.
parameters:
  target:
    type: string
    required: true
    description: Stable identity of the requested target.
required_capabilities: []
---
```

The English body describes scope, prerequisites, identity checks, strategy,
recovery, provenance, limitations and final result checks. App labels and quoted
user data retain their original language. Parameter types are string, integer,
number, boolean, array and object. Validation checks top-level types; nested
business constraints belong in the Skill instructions and scripts.

## Authoring and publication

The skill page offers description-based creation, demonstration and import.
Task reflection can be requested in conversation. All generation uses the ordinary
Agent, which reads the built-in `skill-creator`, source facts and relevant images,
then calls `save_skill`. Automatic draft creation is opt-in and uses the same
Agent in an idle background Turn after the primary task settles, without replaying
the original task. Foreground work takes priority.

Drafts live separately from active packages. Updates require the exact existing
revision. A draft can be inspected, edited, tried and explicitly activated.
Activation publishes exactly the previewed revision and archives the old active
source. Imported files never import local run assessments or demonstration images.
The app keeps the latest 20 assessments visible for each exact revision.
Local enabled/implicit-invocation preferences live outside the portable package.
Changing those preferences preserves the source revision and its assessments.
Exports retain the package's declared defaults, not the user's local overrides.

Demonstrations are recorded inside the virtual viewer. They include ordered action
facts, redacted input references, gesture samples and available before/after frames.
`skill_source` pages through the complete record and reads individual historical
frames. These frames do not establish current state. Multi-pointer/truncated
trajectories and unavailable frames remain evidence gaps. Text redaction does not
redact screenshots; the recording dialog explains this scope.

## Execution and recovery

Composer selections carry an exact name, revision and draft flag through the
queued Turn request, including image preparation. Initial Turn context preloads
selected source text within a budget; partial sources retain retrieval offsets.
Steering a queued selection into an active Turn retains its exact identities,
with source files read through the ordinary tools.

`run_skill` freezes a package revision and validates declared parameters and
capabilities. Without a script path it returns an instruction reference to the
same Agent, avoiding another full body in context. Full reads remain available
after compaction. File reads return bounded assessment summaries on the first
main-document page only; `mode="assessments"` pages through the detailed records.
JavaScript files run in the existing isolated `program` worker with typed
`parameters`, read-only `skill.read(path)` and the existing managed Android bridge.
There is no extra model, dependency installer or additional execution backend.
GUI interpretation returns to the Agent for a fresh observation.

A stable `checkpoint_key` identifies one operation. Use `inspect_only=true` to read
its durable state. Scripts guard each deterministic effect with
`skill.checkpoint(itemIdentity, "begin")`: execute only when `execute` is true,
check the actual receipt, then call `skill.checkpoint(itemIdentity, "complete")`.
Completed items are skipped. Pending items throw `SKILL_CHECKPOINT_EFFECT_UNKNOWN`
and cannot be reset by a script. The Agent must inspect the actual result before
continuing through ordinary tools; it must not bypass this guard with another key.

Explicit `resume=true` only restarts terminal runs that contain item checkpoints,
with identical package revision, entry path and parameters. It does not restore a
JavaScript stack. Nonterminal or uncheckpointed runs cannot be blindly replayed.
A process loss can leave a pending operation; this deliberately requires the Agent
to inspect current state rather than repeating uncertain effects automatically.
Guided runs settle their durable record after assessment or normal task-resource
cleanup. Cleanup closes unassessed runs as unknown, never as semantic success.
Run snapshots stay owned by the Turn; execution does not reload changed source.

`verify_skill` stores this task Agent's assessment against original evidence from
after the run began. It records the exact revision, inputs, task, evidence and
scope. It neither publishes the draft nor completes the user's task. Script exit,
injection success, checkpoint completion and assessment storage are distinct from
actual business-goal completion. The Agent still checks the requested final result.
External assessments require original external evidence. A computation assessment
can cite only the completed Skill script's manifested output; it is recorded with
that distinct evidence kind and cannot certify an external effect. Unknown outcomes
may omit evidence when no observation was obtained.

Task exports include exact selected and started Skill revisions, selection mode,
script/resume flags, reported outcomes, evidence kind, time to assessment, and
file/assessment read counters. Existing task model, tool and total durations remain
task-wide: they must not be misrepresented as per-Skill costs. Unassessed runs remain
explicit, and these metrics do not independently establish success or speedup.

## Built-in packages

The root `skills/` directory is the canonical source for APK built-in packages, not an examples directory. `generateBuiltinSkills` packages it under `assets/skills/`; startup installs trusted `source: system` packages through SkillEngine. Built-ins are read-only and never replace a user-owned installed package with the same name. Knowledge tests read the same source through their configured resource classpath.
