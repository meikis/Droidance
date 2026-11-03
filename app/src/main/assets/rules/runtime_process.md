# Resident runtime transport

The shell process owns the single Agent execution lane, its queue and authoritative storage. Application processes present snapshots and submit explicit commands. Losing a presentation client does not cancel an admitted task. Device reboot or loss of the resident process still interrupts execution and requires normal recovery; no process is immortal.

## transport_policy

{
  "foregroundPollMs": 100,
  "backgroundPollMs": 1000,
  "reconnectMs": 1000,
  "maxPendingTasks": 64,
  "maxRetainedTasks": 64,
  "completedRetentionMs": 600000,
  "maxBufferedChunks": 256,
  "maxBufferedChars": 65536,
  "resultBatchChunks": 64,
  "displayContentPollMs": 1000
}
## Transport semantics

Buffered chunks are presentation data. If a client falls behind, it resumes from the retained chunk cursor and obtains committed conversation facts from the EventStore. Completed RPC receipts are bounded separately from durable task history. An expired receipt never authorizes replaying an already committed Turn.
