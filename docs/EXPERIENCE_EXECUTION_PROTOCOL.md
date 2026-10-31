# Experience and execution boundaries

Droidance uses one task Agent. Prediction is part of its ordinary action decision;
there is no background model reflection, second planner, or app-specific Kotlin workflow.

## Completion and projection

`done.experience_updates` is optional deferred metadata. Its public schema describes
parameterized English methods, applicability, observation boundaries, recovery, and
scoped outcome assessments. Dispatch still validates ordinary completion arguments;
invalid experience metadata does not reject an otherwise valid completion.

Accepted completion calls and their actual installation-catalog snapshots remain in
EventStore. After `TURN_COMPLETED`, an application-owned worker validates and projects
proposals into the Room experience tables. Startup resumes unprojected completed turns.
Each proposal has an immutable source-event/index key; its document, revision snapshot,
FTS entry and receipt commit transactionally. A separate turn receipt closes projection.
No foreground model request waits for this worker.

The projector accepts original, earlier, same-turn external evidence: attached tool
observations, successful external read receipts, or program child images backed by host
artifact manifests. Historical reads, local notes and program stdout are not current
external evidence. A `succeeded` assessment additionally names the exact supported
completion requirement and its evidence. This establishes the task Agent's scoped
assessment and provenance, not an independent guarantee of business success.

Experience IDs describe reusable methods rather than keywords or coordinates. Updates
use compare-and-swap revisions. User disable/delete actions increment the revision;
delete erases projected method bodies and revision snapshots while retaining a tombstone.
Old queued proposals cannot restore a deleted ID. Original task history and previously
exported Skill packages remain separate records.

## Retrieval and management

`session_search(scope="experience")` uses a bounded FTS query and revision-bound cursor.
`scope="all"` includes a separate bounded `experience_page`; its cursor belongs to the
experience scope. Exact detail references have the form `experience:id:revision`.
Inactive, deleted or stale references do not expand. Turn context includes bounded title
and applicability hints, not full methods or historical product facts.

Package applicability is Agent-authored. Version snapshots come from the local Android
package catalog at completion and retrieval; absent versions remain unknown. A matching
local installation version does not prove that a virtual execution space or visible UI
matches. Current observations always determine whether the method applies. Optional
experience-context failures leave the ordinary profile/episode context available.

The preferences and memory screen exposes only execution preferences (`SOUL.md`),
the user profile (`USER.md`) and saved memories (`MEMORY.md`). Experience retrieval
and projection remain runtime capabilities without a separate settings page.
Background projection creates no task card or notification. User-profile memory
retains its separate user-source-quote contract.

`save_skill(source_experience_refs=[...])` freezes exact enabled method revisions and
source metadata into `references/experience-N.md` inside the pending package. Exported
packages therefore do not depend on a local experience database. Publication remains
an explicit action in the existing Skill workflow; draft saving does not replace active
instructions.

## Batches and script continuation

A batch groups short actions already grounded in the inspected screen. Existing
per-action surface/pixel checks and final-frame capture remain active. A prediction
that opening a panel will reveal fields is an intention, not a grounded target. The
Agent must inspect that new state before supplying coordinates.

At a program visual boundary, the host returns the pending request hash, boundary ID,
observation reference and completed/unknown counts. `poll` must supply a `resume` object
with `boundary_id`, `pending_action_hash`, `observation_ref`, and `decision`:

- `continue` approves exactly the returned pending request using a fresh, bound image.
- `replan` closes the worker and discards its unexecuted remainder. Settled receipts and
  task files remain available to the same Agent.

A fresh screenshot alone is insufficient. Backend/execution/display identity must
match. Input queued before a visual boundary is cleared. New `stdin` can supply new
arguments only when the script intentionally waits at `readLine()`; it cannot rewrite
an already compiled, pending Android call. Unknown effects stop continuation and must
be inspected before a new plan. Dynamic future script requests are never reported as
an enumerated executed suffix.

## Measurement

Existing task exports retain model, tool, total and human-wait durations. Added fields
report batch primitive attempts and successful receipts, batch duration, pre-action
sampling, frame wait, experience detail hits/version mismatches, explicit program resume
decisions and program-child diagnostics. Program child metrics are counted once even
when a separate settlement exists. These counters do not label semantic success.

End-to-end speed claims require paired real tasks with comparable provider, device,
app state and task goals, including retries and independent result inspection. Unit
checks, Room/worker instrumentation and APK builds establish narrower implementation
properties and cannot substitute for that acceptance.
