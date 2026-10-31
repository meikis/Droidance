# Droidance Built-in Deep Link Catalog

This directory contains built-in route knowledge imported into `droidance-data/deeplinks` and retrieved on demand. The catalog currently contains 104 routes, 47 app names, and 47 Android packages. `routes.jsonl` is the authoritative route inventory.

## Route selection

When enabled, prefer an applicable catalog route for app navigation before the launcher fallback. The initial installed-app action index supplies action names and required parameters. The Agent supplies the target app, semantic action and grounded parameters. `android_act(op="open")` resolves the URI through the catalog. Select relevant native platform APIs or explicit platform Intents first for system capabilities. A Deep Link is a navigation method, not a data read or a completion verdict.

Use `appName`, `appAliases`, `packageName`, `action` and `actionAliases` to identify a route. Preserve original app names, localized action labels, identifiers, URI templates and parameter values exactly; these are lookup data, not English prose to rewrite. Do not invent a route when the catalog has no match.

## Reliability and evidence

Only routes traceable to official HTTPS documentation may be labelled official. Undocumented schemes remain candidates: initial exploration is allowed, but routes with more failures than successes are excluded from automatic resolution. Apps without a stable public scheme use an official web or App Link entry where available. Respect Android App Links verification and inspect the destination package and page after launch.

A delivered Intent or accepted URI does not establish correct arrival or task completion. Verify the destination, requested fields and final state with current evidence. If a route is unavailable or fails verification, use another applicable direct capability or grounded GUI fallback. Record the concrete failure without retrying equivalent launches indefinitely.

## Coverage

The catalog includes shopping, food delivery, social and messaging apps, media, payments, maps and navigation, productivity, and travel. Query `routes.jsonl` for the exact inventory rather than treating this category summary as evidence that a particular route is supported or verified.
