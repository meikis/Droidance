# Alarms and timers

Read the current device time and time zone with `android_read(op="device")` when resolving
relative times. Use the user's date, local time, repetition and label. The platform alarm
contract accepts a time of day, not an arbitrary calendar date. For a one-time alarm,
check that its next occurrence is the requested date. A later date may require a supported
app feature; never turn it into an unintended weekly recurrence.

## Create an alarm

Send `android_act` with this shape, replacing the example hour, minute and label:

```json
{
  "op": "open",
  "effect_key": "requested-alarm-1",
  "intent_action": "android.intent.action.SET_ALARM",
  "int_extras": {
    "android.intent.extra.alarm.HOUR": 9,
    "android.intent.extra.alarm.MINUTES": 0
  },
  "string_extras": {
    "android.intent.extra.alarm.MESSAGE": "Requested alarm"
  },
  "boolean_extras": {
    "android.intent.extra.alarm.SKIP_UI": true
  },
  "expect_screen_change": false
}
```

`HOUR` is an integer from 0 through 23; `MINUTES` is an integer from 0 through 59.
`MESSAGE` is a String and `SKIP_UI` is a Boolean. Omit an optional label when none is
needed. `SKIP_UI=true` requests setting the alarm without a confirmation interface.
Without it, an app may set the alarm or show an editor; never assume either result.

Inspect the dispatch receipt for actual typed options, stdout/stderr and warnings.
If an editor appears with different values, check argument names and types before
concluding the vendor ignored a valid request. A type-correct call can still encounter
an app limitation. Preserve the observed limitation instead of inventing success.

For verification, open the alarm list using:

```json
{"op":"open","intent_action":"android.intent.action.SHOW_ALARMS"}
```

Inspect the requested time, enabled state, repetition, label when requested and next
firing date. `android_read(op="system", service="alarm")` may expose a next trigger,
but a single next trigger cannot verify all requested alarms or labels. There is no
documented universal alarm ContentProvider in this method; do not invent one.

For several alarms, derive the full requested set first, then issue one well-formed
native call per item and retain each receipt. Once that method works on the device,
reuse it for the remaining items rather than editing each time wheel. Stop to inspect
an inconsistent or uncertain result before continuing mutations. Verify every item;
do not resend accepted calls merely because verification is pending. A next-firing
countdown is supporting evidence, not the identity of every saved item. Locate the
original item before modifying a different existing alarm; preserve list coverage and
never create again solely because the item is below the current viewport.

`android.intent.extra.alarm.DAYS` is an integer ArrayList of repeating weekdays, not
an arbitrary date. The current tool's typed-extra maps expose scalars, not integer
lists. Do not encode DAYS as text or a single integer. Use a documented available
method for repetition or the actual repetition controls.

## Timers

Use the same Boolean background contract with an integer length in seconds:

```json
{
  "op": "open",
  "effect_key": "requested-timer-1",
  "intent_action": "android.intent.action.SET_TIMER",
  "int_extras": {"android.intent.extra.alarm.LENGTH": 300},
  "string_extras": {"android.intent.extra.alarm.MESSAGE": "Requested timer"},
  "boolean_extras": {"android.intent.extra.alarm.SKIP_UI": true},
  "expect_screen_change": false
}
```

Replace the example duration and label with the request. Use
`android.intent.action.SHOW_TIMERS` to inspect the active timer and actual remaining
time. Do not create a second timer to verify the first.

Source: [Android common Intents — alarms and timers](https://developer.android.com/guide/components/intents-common#CreateAlarm).
