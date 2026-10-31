# Language Policy

## User interface

Use Android string resources for user-facing labels, instructions, permission notices, status messages, notifications and sharing dialogs. Use English in the default `values/strings.xml` and complete Simplified Chinese translations in `values-zh/strings.xml`. Keep keys and format arguments aligned. Chinese mode uses natural Simplified Chinese; English mode uses standard English. Preserve product names, API identifiers, URLs, filenames and quoted user or source content when translation would change their identity or meaning.

Represent runtime conditions with typed states. Map them to localized resources at the presentation boundary; do not display internal English diagnostic sentences as ordinary UI guidance. Keep detailed diagnostic output in the existing logs and debug exports. Android and AndroidX own the application locale preference and persistence. Use AppCompatActivity for localized activities, AppCompatDelegate for the language picker, and ContextCompat.getContextForLanguage/getString for presentation outside an Activity. An empty application locale list means “Follow system”; resolve it from the system language list, not a process locale changed by the app. Do not maintain a separate language preference or call Locale.setDefault.

A locale change refreshes already-visible notification text, channel names, RemoteInput labels and runtime island status labels. Retain notification intents, user-authored text, generated task titles and remaining expiry. Do not replay pairing or task events to translate a notice, or restore a notification the user dismissed. Use plurals resources for quantity-dependent sentences, and keep format arguments identical across plural forms and translations.

## Agent and reusable documentation

Write agent instructions, reusable Skill methods and newly authored engineering Markdown in standard English unless the user explicitly requests another language. Keep the agent’s public replies in the resolved user language. Localized public-notice dictionaries intentionally contain both languages.

Preserve app names, route labels, source quotations, evidence, runtime values and user-authored documents exactly. Do not translate lookup identifiers, commands or protocol markers. Historical audits and acceptance records may retain their original language; avoid rewriting evidence solely for stylistic consistency. Repository collaboration instructions remain authoritative.

## Verification

Check resource keys and formatting arguments in both locales, exercise typed-state presentation coverage, and build the APK. Device acceptance should cover an English system with Chinese selected, a Chinese system with English selected, and returning to “Follow system”. Inspect unavailable virtual-space execution, scheduled-task setup, permission handoff, background notices and share dialogs. Unit tests and a successful build do not establish visual or live language-switching acceptance.
