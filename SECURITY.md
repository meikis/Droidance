<!--
  SPDX-FileCopyrightText: 2026 YBBLab
  SPDX-License-Identifier: Apache-2.0
-->

# Security Policy

Droidance runs with broad device permissions (SMS, contacts, call log, a shell-identity
sidecar process, and an AI agent that can operate other apps). Security reports are taken
seriously.

## Reporting a vulnerability

Please **do not** open a public issue for security problems. Use GitHub's private
vulnerability reporting for this repository (Security → Report a vulnerability) and include:

- affected version / commit and device Android version
- a description of the impact and the steps to reproduce
- whether the issue needs a malicious app or a malicious model response

You can expect an acknowledgement within a few days. Fixes are published together with a
release note once a patched build is available.

## Scope notes

- API keys and channel secrets are stored in app-private storage. Provider credentials are also
  passed to the resident shell-identity runtime in memory while it runs. Treat a rooted or
  debuggable device as untrusted.
- Runtime history, diagnostics and other task data are stored in a shell-owned directory protected
  from ordinary apps. Shell or Root access can read it, and uninstalling the app does not remove it.
  Older versions also retained provider and channel credential copies there. The updated runtime
  removes those copies for the current installation when it starts; directories from earlier
  uninstalled installations are not automatically removed.
- Debug builds expose extra test components and are not intended for daily use.
