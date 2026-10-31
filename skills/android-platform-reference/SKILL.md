---
name: android-platform-reference
version: 1
source: system
description: Reference for standard Android system intents and settings screens that can replace GUI steps for alarms, timers, calls, messages, calendar, camera and settings.
display_names: {"zh": "Android 平台参考", "en": "Android platform reference"}
display_descriptions: {"zh": "常用 Android 系统 Intent 与设置页面速查，可替代部分界面操作。", "en": "Quick reference for common Android system intents and settings screens that can replace GUI steps."}
required_capabilities: ["android_act", "android_read"]
parameters: {}
---

# Android platform reference

This is a reference, not a workflow. It lists standard platform entry points. It never
authorizes an action and never proves that a route works on the current device: check the
current screen after every launch and fall back to the GUI when the route is missing,
redirected, or ignored by the device vendor's app.

## How to use

1. Prefer a direct route only when it removes several fragile GUI steps.
2. A direct route that opens a screen is not the same as completing the task. Inspect the
   resulting screen and confirm the requested values before any confirming tap.
3. Never send a message, place a call or change a setting merely because a route
   pre-filled the fields; those remain explicit user-approved actions.
4. Vendor apps may handle the same intent differently or reject extras. Treat every route
   below as best effort.

## Clock

| Goal | Action | Notable extras |
| --- | --- | --- |
| Create an alarm | `android.intent.action.SET_ALARM` | `android.intent.extra.alarm.HOUR`, `...MINUTES`, `...MESSAGE`, `...DAYS`, `...SKIP_UI` |
| Create a timer | `android.intent.action.SET_TIMER` | `android.intent.extra.alarm.LENGTH` (seconds), `...MESSAGE`, `...SKIP_UI` |
| Show alarms | `android.intent.action.SHOW_ALARMS` | none |
| Show timers | `android.intent.action.SHOW_TIMERS` | none |

Without `SKIP_UI` the clock app opens pre-filled and waits for confirmation.

## Phone and messages

| Goal | Action / URI | Notes |
| --- | --- | --- |
| Open dialer with a number | `android.intent.action.DIAL`, `tel:<number>` | Does not place the call. |
| Place a call | `android.intent.action.CALL`, `tel:<number>` | Needs the call permission; high risk, requires explicit user approval. |
| Compose an SMS | `android.intent.action.SENDTO`, `smsto:<number>`, extra `sms_body` | Opens the composer only. |
| Compose an email | `android.intent.action.SENDTO`, `mailto:<address>`, extras `android.intent.extra.SUBJECT`, `android.intent.extra.TEXT` | Opens the composer only. |

## Calendar and contacts

| Goal | Action | Notes |
| --- | --- | --- |
| New calendar event | `android.intent.action.INSERT`, data `content://com.android.calendar/events` | Extras: `title`, `description`, `eventLocation`, `beginTime`, `endTime` (epoch millis), `allDay`. |
| View calendar | `android.intent.action.VIEW`, data `content://com.android.calendar/time/<epoch-millis>` | |
| New contact | `android.intent.action.INSERT`, data `content://com.android.contacts/contacts` | Extras: `name`, `phone`, `email`. |

## Media and web

| Goal | Action | Notes |
| --- | --- | --- |
| Open a web page | `android.intent.action.VIEW`, `https://...` | Opens the default browser. |
| Web search | `android.intent.action.WEB_SEARCH`, extra `query` | |
| Take a photo | `android.media.action.IMAGE_CAPTURE` | Returns to the caller when completed. |
| Record a video | `android.media.action.VIDEO_CAPTURE` | |
| Map / navigation | `android.intent.action.VIEW`, `geo:<lat>,<lng>?q=<query>` or `google.navigation:q=<destination>` | The installed map app decides the behavior. |
| Share text | `android.intent.action.SEND`, type `text/plain`, extra `android.intent.extra.TEXT` | Opens the system share sheet. |

## Settings screens

All are `android.settings.*` actions that open a screen without changing anything:

| Screen | Action |
| --- | --- |
| Main settings | `android.settings.SETTINGS` |
| Wi-Fi | `android.settings.WIFI_SETTINGS` |
| Bluetooth | `android.settings.BLUETOOTH_SETTINGS` |
| Airplane mode | `android.settings.AIRPLANE_MODE_SETTINGS` |
| Display | `android.settings.DISPLAY_SETTINGS` |
| Sound | `android.settings.SOUND_SETTINGS` |
| Battery saver | `android.settings.BATTERY_SAVER_SETTINGS` |
| Date and time | `android.settings.DATE_SETTINGS` |
| Language | `android.settings.LOCALE_SETTINGS` |
| Application details | `android.settings.APPLICATION_DETAILS_SETTINGS`, data `package:<package-name>` |
| Notification listeners | `android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` |
| Developer options | `android.settings.APPLICATION_DEVELOPMENT_SETTINGS` |

Changing a setting still requires operating the screen and verifying the result visually.

## Limits

- Action names above are public platform constants; extras and URI forms can differ in
  vendor apps. Re-verify on the current device.
- The list is intentionally small. For a specific installed app, prefer its documented deep
  links from the app's own route index when one exists.
