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

## Verify without GUI

On Android 11 (API 30) and later, use `android_read(op="device")` and inspect
`next_alarm_clock`, sourced from the public `AlarmManager.getNextAlarmClock()` API
(available since API 21). This read requires no exact-alarm scheduling permission,
opens no app and captures no screen. `status="scheduled"` supplies `trigger_at_ms`
as epoch milliseconds; compare it deterministically with the requested next date and
time in the device time zone. A matching next trigger supports that time being scheduled.
Before changing anything, reuse an already matching scheduled result when it satisfies
all requested conditions. Never infer label, recurrence or object identity from a timestamp.

`status="none"` means this API returned no next alarm; it is not an inventory of every
clock app's saved or disabled alarms. `status="unavailable"` is an unknown reading,
not an empty list. An earlier alarm can hide the requested later alarm from this read.
There is no public cross-app API to enumerate all saved alarms or their labels.

Do not launch SHOW_ALARMS, take screenshots or navigate a clock UI merely to verify a
native operation. Keep the typed dispatch receipt and native readback. If these cannot
establish a requested field, state exactly what the platform accepted and what remains
unconfirmed, without replaying the mutation or manufacturing GUI evidence. GUI is only
for an operation the available official methods cannot perform, or explicit visual review.

For several alarms, derive the full requested set first, then issue one well-formed
native call per item and retain each receipt. Once that method works on the device,
reuse it for the remaining items rather than editing each time wheel. Stop to inspect
an inconsistent or uncertain result before continuing mutations. Keep each item's native
receipt and the actual coverage of its readback; a single next trigger does not verify
a whole set. Do not resend accepted calls merely because verification is pending. A
next-firing countdown supports timing, not the identity of every saved item. Preserve
the original effect identity and never create again to compensate for limited readback.

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
time only when the user explicitly requests visual inspection. Android has no universal
public timer-state query. Keep the accepted native receipt and report any unconfirmed
running-state details without opening GUI or creating a second timer for verification.

Sources: [Android common Intents — alarms and timers](https://developer.android.com/guide/components/intents-common#CreateAlarm),
[AlarmManager.getNextAlarmClock](https://developer.android.com/reference/android/app/AlarmManager#getNextAlarmClock()),
[AlarmClock contracts](https://developer.android.com/reference/android/provider/AlarmClock).

## Official operation coverage on Android 11+

- Create/activate: `ACTION_SET_ALARM` with typed time fields and `SKIP_UI=true`.
- Read next firing: the device read above; not a full alarm database query.
- Dismiss: `android.intent.action.DISMISS_ALARM` (API 23+) with an exact observed alarm
  URI, or documented `android.intent.extra.alarm.SEARCH_MODE` and grounded search fields.
  Time search uses String `android.time`, integer `HOUR`/`MINUTES` and Boolean
  `android.intent.extra.alarm.IS_PM`. Label search uses String `android.label` and the
  grounded `MESSAGE`; it may match multiple alarms. Never use an all-alarms search for
  a request concerning one alarm. Dismissal is not a promise to delete saved history.
- Arbitrary existing-record updates, full listing and permanent deletion: no universal
  public cross-app CRUD contract. Do not invent a clock ContentProvider, reuse another
  app's private URI, read private databases or replace the user's clock with alarms owned
  by Droidance. `SET_ALARM` may create/reuse an alarm; it is not an arbitrary update by ID.

Calendar and contacts have their own permission-bound Provider CRUD contracts. Do not
infer the same coverage for every Android feature just because it has a permission.
