# Device commands and calculations

Shell belongs to system actions when it queries or operates the Android device or
calculates values required by a device task. Pure computation and ordinary workspace
file processing can use `program` / `workspace` independently of this Skill.

Use covered typed facts from `android_read(op="device")` first. Use
`android_read(op="system", service=...)` for an argument-free service dump. Use Shell
for missing fields, documented command arguments or deterministic calculations.

## Invocation

`shell` executes Android sh, not the computer's terminal. Supply the exact command
without `adb shell`. For nonvisual work, explicitly set `expect_screen_change=false`:

```json
{"command":"getprop ro.product.model","expect_screen_change":false}
```

The frozen backend supplies actual UID and authority. Do not assume Root, desktop
utilities or App-UID permissions. Preserve `stdout`, `stderr`, exit code, execution
status, units, scope and truncation. A command's exit code verifies execution, not
all pipeline members or the user's goal. Use scoped output and explicit checks when
combining dependent commands; stop on the relevant error.

## Time conversion

Read the device time zone, resolve the requested local calendar date, then calculate
with that explicit zone. For Android devices whose `date` supports this syntax, this
example converts 2030-01-02 14:00 in Asia/Shanghai to epoch seconds:

```json
{"command":"TZ='Asia/Shanghai' date -d '2030-01-02 14:00:00' +%s","expect_screen_change":false}
```

Replace the example zone/date with grounded values. Parse the actual numeric output
and multiply seconds by 1000 in deterministic computation for a millisecond contract.
If the device rejects the syntax, preserve the error and choose supported computation;
never treat an error as a timestamp. Respect daylight-saving transitions and ambiguous
local times; do not compute arbitrary future local dates by blindly adding 86400000.

## Side effects and recovery

Use a documented native tool for an available Intent or Provider operation so typed
arguments and structured receipts remain available. Do not reissue the same mutation
through Shell merely because its result is not verified. Device commands can have
side effects even with `expect_screen_change=false`. Verify the relevant resulting
state and keep per-item identities when a script handles several objects.

After cancellation, timeout or partial failure, inspect actual state before choosing
remaining work. Do not replay a whole script to fix its final output. A permission
denial must not be worked around with `su`, private databases or another identity.
Use bound Android screen tools for GUI actions; a device-wide Shell is not a display
binding for input. Data pagination and dependent computation can use one `program`
invocation with `android.call("shell", arguments)` when available, keeping original
receipts and ending the script at a new visual decision.
