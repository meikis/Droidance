---
name: android-system-actions
version: 2
source: system
description: Use Android system interfaces, ContentProviders, Intents and device Shell commands for alarms, timers, calendar events, contacts, messages, settings and device data; verify the actual result.
display_names: {"zh": "安卓系统操作", "en": "Android system actions"}
display_descriptions: {"zh": "通过系统接口与设备命令处理闹钟、日程、联系人等任务，并核验结果。", "en": "Use system interfaces and device commands for alarms, calendar, contacts and device tasks, with result verification."}
required_capabilities: ["android_act", "android_read"]
parameters: {}
---

# Android system actions

Use the documented device capability that satisfies the user's goal before navigating
screens. This includes ContentProvider reads/writes, platform Intents and device Shell.
Choose a method from the actual task and available tools; this is not a probe checklist.

Read only the relevant method before execution:

- Alarms or timers: [references/clock.md](references/clock.md).
- Calendar events: [references/calendar.md](references/calendar.md). Creation normally
  uses a Provider write and readback; opening an event editor is a separate method.
- Contacts, SMS or email: [references/contacts-and-messages.md](references/contacts-and-messages.md).
- Settings, media, web or maps: [references/routes.md](references/routes.md).
- Device queries, commands or deterministic time calculations:
  [references/shell.md](references/shell.md), when Shell is available.

## Execution and recovery

1. Ground the requested object, values and time zone. A user's request to create or change
   something authorizes that described operation; do not ask again merely because an
   interface can perform it directly. Missing account/calendar identity or another
   materially ambiguous input may need clarification. System permission approval remains
   a distinct platform boundary.
2. Use `android_read` for its covered facts and Provider records, `android_act(op="content")`
   for documented Provider mutations, and `android_act(op="open")` for Intents. Use Shell
   for documented device commands or calculations that these tools do not cover. Preserve
   the bound execution context and the user's selected app or account.
3. Match every Intent extra's exact platform type: `string_extras` for text, `int_extras`
   for 32-bit integers, `long_extras` for 64-bit integers, and `boolean_extras` for
   booleans. Numeric and boolean values are unquoted JSON values. Each key occurs in
   only one map. A number written as text in `string_extras` stays an Android String;
   the receiving app is not required to convert it. Provider `values` instead uses its
   own documented column names and native JSON scalar types.
4. Verify the requested effect from a scoped readback or the actual current interface.
   Keep inserted record URIs, affected-row counts and per-item receipts. An Intent
   dispatch receipt records what was sent and command output; it does not prove that
   the receiving app applied the extras or saved a record. A successful data operation
   and matching readback need no terminal screenshot.
5. If fields are ignored, inspect names, types, required data/MIME fields and dispatch
   warnings first. Repair an editor-opening call when no record has been saved. For
   any call that may already have created or changed something, inspect its external
   state before retrying. Preserve uncertain effects; do not create a duplicate to test
   whether the first operation worked.
6. Use GUI for a concrete missing feature or verification gap after a well-formed direct
   method proves unsuitable. A permission denial must not be evaded by switching to
   Root, another UID or private files. A documented public editor may be offered as a
   separate user-visible method that keeps the platform's own authorization boundary.

Report what was actually completed and what remains unconfirmed. Treat each requested
item independently when a task contains several alarms, records or changes.
