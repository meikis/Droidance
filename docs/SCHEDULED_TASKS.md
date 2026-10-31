# Scheduled tasks

The Schedule screen and the `automation` tool share `AutomationScheduleInput` and the same persistent manager. They support one-time deadlines, daily/weekly calendar rules, and fixed intervals of 1 to 5,256,000 minutes. Optional repetition limits use either an inclusive end instant or a planned trigger count. Existing daily/weekly fields retain their meaning in the same state document.

## Time ownership

Natural-language `delay_minutes` is relative to the host-recorded user message (the draft submission clock or the latest user message/steer event in the active turn). The model never owns that clock. The editor also offers an explicit delay after enabling; it resolves that delay when saving. `run_at` is an absolute ISO-8601 instant with offset. The stored time zone governs calendar schedules and presentation; changing the device time zone does not silently move a plan to a different time zone.

Calendar rules rebuild the local date and time for every deadline. Fixed intervals retain their initial anchor. One-time plans have no next deadline after their scheduled occurrence. Expired one-time drafts cannot be enabled. Finished plans require an edited schedule to rearm.

## Durable dispatch

`AutomationStore.consumeDeadline` verifies plan identity, version and expected cursor, then commits occurrence creation/merging, trigger count and next deadline in one atomic file replacement. AlarmManager wake-ups and the live coordinator use this same entry point. At most one pending occurrence is retained per plan; a running occurrence and one pending occurrence may coexist. Merged deadlines are counted and recorded. Execution snapshots preserve the work across edits. Reports appear in the app and local Android notifications.

`RUN_ONCE` coalesces overdue work into one execution. `SKIP` skips work more than one minute late, checked both when dispatched and when claimed after queueing. A grouped recovery checks its latest eligible deadline. Count limits include skipped and merged deadlines; manual runs do not consume the scheduled trigger budget. Updating or pausing cancels queued work with a history record, while running work retains its snapshot. Deleting a plan preserves in-flight execution and delivery records until they finish.

The history limit applies only to terminal records without pending delivery. Interrupted execution is unknown and is never automatically replayed. Notification delivery retries are independent of device execution. The stored Agent terminal result is not independent proof of business success.

## Platform limits

Exact alarm permission is an alarm capability, not a guarantee of punctual device work. Android idle restrictions and device contention can delay execution. The live coordinator waits for the next deadline without maintaining a new wake lock. Boot, time changes and exact-alarm permission broadcasts restore persisted schedules. Existing execution service/device preparation still owns waking and using the selected device.

## Verification scope

Pure schedule/protocol tests cover time arithmetic, relative anchors, recurrence endings and malformed input. Android instrumentation uses the real file store and manager to cover atomic restart recovery, duplicate callbacks, merged deadlines, cancellation, immutable work snapshots and retention. Building or compiling instrumentation does not establish device wake-up or real-model task success; those require separate live evidence.
