# Calendar events

Prefer the app-UID Calendar Provider for authorized event creation, updates and reads.
It persists records directly and returns an identity for exact readback. The tools
request missing `READ_CALENDAR` / `WRITE_CALENDAR` permissions and resume the call when
granted. An editor-opening Intent is a separate fallback, not a background insert.

## Resolve the event

Use the requested title, date, start/end time, time zone, destination calendar and any
requested reminders or recurrence. Resolve relative dates from current device time.
Calculate epoch milliseconds deterministically with an explicit time zone; see
[shell.md](shell.md) if using a device command. Do not estimate timestamps.

Preserve a supplied title. For a time-only request, a neutral title in the interface
language can name the event without inventing its purpose. Ask only for inputs that
materially affect the result, such as an ambiguous destination calendar/account.
Keep an explicitly requested all-day event distinct from a timed event. For timed,
non-recurring events, require an end after the start.

## Find a writable calendar

Query only calendars in the relevant scope, for example:

```json
{
  "op": "content",
  "uri": "content://com.android.calendar/calendars",
  "projection": ["_id", "calendar_displayName", "calendar_access_level", "visible"],
  "selection": "calendar_access_level >= ? AND visible = ?",
  "selection_args": ["500", "1"],
  "limit": 50
}
```

Use `android_read` for this call. Follow pagination when more candidates exist. Access
level 500 is contributor access, which permits adding events. Honor a requested calendar;
visibility alone is not write permission. Use the one eligible calendar, or a currently
grounded default/selected calendar. If several materially different accounts remain,
ask which one. Never guess a calendar ID or create an account/calendar to avoid this choice.

## Insert and read back

Call `android_act`. This example is a timed event on 2030-01-02, 14:00–16:00 in
Asia/Shanghai. Replace every example value, especially calendar ID and timestamps,
with the current request and query results:

```json
{
  "op": "content",
  "action": "insert",
  "uri": "content://com.android.calendar/events",
  "values": {
    "calendar_id": 42,
    "title": "Requested event",
    "dtstart": 1893564000000,
    "dtend": 1893571200000,
    "eventTimezone": "Asia/Shanghai",
    "allDay": 0
  }
}
```

Keep the returned `inserted_uri`. Read that exact URI with `android_read(op="content")`
and projection `["_id","calendar_id","title","dtstart","dtend","eventTimezone","allDay"]`.
Compare the persisted title, calendar, instants and time-zone interpretation with the
request. Successful insertion followed by matching readback establishes this record;
opening the calendar is unnecessary for completion. It does not establish server sync
or reminder delivery unless those were separately verified.

If a reminder is requested, insert into `content://com.android.calendar/reminders`
with the observed `event_id`, integer `minutes` and supported integer `method`
(`1` means alert). Query the calendar's allowed reminder methods when needed and
read back the reminder linked to this event. Event creation alone does not prove a
requested reminder exists. Retain a successfully created event if reminder setup
fails; repair that event's reminder instead of recreating the event.

For edits/deletion, first identify the exact existing record. Mutate its URI and
inspect the affected-row count and resulting state. Never issue a broad selection
when the user asked to change one event. A timeout or failed receipt may follow a
completed write: query the intended calendar, title and time window before retrying;
resolve any matching records without deleting or recreating unrelated events.

## All-day or recurring events

For all-day Provider events, use `allDay=1`, `eventTimezone="UTC"`, UTC-midnight date
boundaries and an exclusive end date. A one-day event ends at the next UTC midnight.
Do not apply the timed-event offset conversion to all-day dates.

Recurring Provider events use `dtstart`, `eventTimezone`, `duration` and a grounded
`rrule` or `rdate`; do not substitute the non-recurring `dtend` shape. Verify actual
occurrences with the Instances endpoint for the requested range. Do not infer all
future occurrences from one stored recurrence string.

## Open a prefilled editor when necessary

When direct persistence is unavailable or the user requests reviewing an editor, use
`android_act` with exact typed extras:

```json
{
  "op": "open",
  "intent_action": "android.intent.action.INSERT",
  "uri": "content://com.android.calendar/events",
  "string_extras": {"title": "Requested event"},
  "long_extras": {"beginTime": 1893564000000, "endTime": 1893571200000},
  "boolean_extras": {"allDay": false}
}
```

`beginTime` and `endTime` are Long epoch milliseconds, not Strings. They are Intent
extras; Provider columns instead use `dtstart` and `dtend`. Optional text extras are
`description` and `eventLocation`. Inspect title, date, times and calendar in the
returned editor. If timestamps were passed in `string_extras`, repair their types
before replacing the method with date-picker navigation. An unsaved editor is not
a created event. Save only the requested event and verify the persisted result.

A denied Provider permission is not permission to use Shell, Root or another UID to
read/write that Provider. A public editor keeps its own user-facing permissions;
offer it as a distinct method when appropriate.

Source: [Android Calendar Provider](https://developer.android.com/identity/providers/calendar-provider).
