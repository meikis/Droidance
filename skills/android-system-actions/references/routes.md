# Public system entry points

Use `android_act(op="open", intent_action=..., uri=..., mime_type=..., ...)` with the
exact contract. Preserve an explicitly selected target package. An accepted launch
establishes delivery only; inspect the destination and the requested parameter values.
For installed-app routes, honor the maintained route catalog and Deep Link setting.

## Web, maps and media

| Purpose | Intent contract | Completion evidence |
| --- | --- | --- |
| Open web page | `android.intent.action.VIEW`, requested `https://...` URI | Requested page actually loaded |
| Web search | `android.intent.action.WEB_SEARCH`, String extra `query` | Requested query/results in the actual destination |
| Map location | `android.intent.action.VIEW`, properly encoded `geo:<lat>,<lng>?q=<query>` | Requested location and chosen app |
| Navigation | `android.intent.action.VIEW`, encoded `google.navigation:q=<destination>` when supported | Actual destination and navigation state |
| Share text | `android.intent.action.SEND`, `mime_type="text/plain"`, String extra `android.intent.extra.TEXT` | Share sheet is only preparation; requested destination and completed send need verification |
| Camera | `android.media.action.IMAGE_CAPTURE` | Requested captured media, not just camera launch |
| Video camera | `android.media.action.VIDEO_CAPTURE` | Requested saved recording, not just camera launch |

The launch tool does not expose an Activity-result callback or automatically return
camera media to the workspace. Use the real camera controls and inspect the saved
result as required; do not promise a returned file based solely on an accepted Intent.

## Settings

These `intent_action` values open screens without themselves changing a setting:

| Screen | Action / data |
| --- | --- |
| Main settings | `android.settings.SETTINGS` |
| Wi-Fi | `android.settings.WIFI_SETTINGS` |
| Bluetooth | `android.settings.BLUETOOTH_SETTINGS` |
| Airplane mode | `android.settings.AIRPLANE_MODE_SETTINGS` |
| Display | `android.settings.DISPLAY_SETTINGS` |
| Sound | `android.settings.SOUND_SETTINGS` |
| Battery saver | `android.settings.BATTERY_SAVER_SETTINGS` |
| Date/time | `android.settings.DATE_SETTINGS` |
| Language | `android.settings.LOCALE_SETTINGS` |
| App details | `android.settings.APPLICATION_DETAILS_SETTINGS`, `uri="package:<observed-package>"` |
| Notification listeners | `android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` |
| Developer options | `android.settings.APPLICATION_DEVELOPMENT_SETTINGS` |

Use available native reads for a requested setting's state before opening a screen.
When an authorized change has a documented available device command, that can replace
GUI editing; inspect its actual capability and permissions and verify the resulting
state. Otherwise operate the observed settings controls. Do not invent a write command
from a screen action name or broaden the requested setting change.

Source: [Android common Intents](https://developer.android.com/guide/components/intents-common).
