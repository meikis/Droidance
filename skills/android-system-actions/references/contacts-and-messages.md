# Contacts and messages

## Contacts

Use `android_read(op="content")` for scoped contact reads. For example,
`content://com.android.contacts/data/phones` exposes `_id`, `contact_id`,
`display_name` and `data1` (phone number). Use a selection and only the columns needed;
phone-row IDs and contact IDs are different identities. Follow pagination when needed.
The tools request missing contact permissions through the app-UID bridge.

For direct creation, a contact consists of a `raw_contacts` record and linked `data`
rows. Resolve the intended account first. A local contact can be created with
`android_act(op="content", action="insert", uri="content://com.android.contacts/raw_contacts",
values={"account_name":null,"account_type":null})`. Retain its returned raw-contact ID.
Add only requested fields through `content://com.android.contacts/data`, using that
integer `raw_contact_id`, a `mimetype` and the appropriate data columns:

| Field | mimetype | Values |
| --- | --- | --- |
| Name | `vnd.android.cursor.item/name` | `data1`: requested display name |
| Phone | `vnd.android.cursor.item/phone_v2` | `data1`: requested number; `data2`: grounded phone type if needed |
| Email | `vnd.android.cursor.item/email_v2` | `data1`: requested address |

These data rows can use the tool's `action="batch"`,
`authority="com.android.contacts"`, and `operations` with `type="insert"`, `uri` and
`values` after the raw-contact identity is known. Read the exact created raw contact
and linked data rows back before reporting completion. If later fields fail, preserve
the original raw-contact identity and repair its missing rows; do not create another
contact. Do not report a successful raw-contact insert as a complete contact.

For a user-requested editor or an unavailable direct write, use this `android_act`
shape, substituting the actual requested values:

```json
{
  "op": "open",
  "intent_action": "android.intent.action.INSERT",
  "uri": "content://com.android.contacts/contacts",
  "mime_type": "vnd.android.cursor.dir/contact",
  "string_extras": {"name": "Requested name", "phone": "+1234567890"}
}
```

Optional `email` is also a String. Inspect the fields and account, save the authorized
contact and verify it. Opening this composer does not create the contact.

## SMS and email

Read SMS with `android_read(op="content", uri="content://sms/inbox", ...)` and scoped
projection, selection and pagination. Common columns are `_id`, `address`, `body`,
`date` and `read`. An inbox query excludes sent messages. Retain coverage and source
identities; permission denial is not an empty inbox. Use `program` for bulk pagination
and deterministic summaries when helpful, not a GUI tour of unchanged records.

To compose an authorized SMS, use `intent_action="android.intent.action.SENDTO"`,
`uri="smsto:<recipient>"` and `string_extras={"sms_body":"<requested text>"}`.
For email use `mailto:<recipient>` and String extras `android.intent.extra.SUBJECT`
and `android.intent.extra.TEXT`. Correctly encode URI values and preserve recipients.
Both open composers; verify the recipient and body before sending an authorized
message and inspect the actual sent state. Provider insertion of an SMS row is not
message transmission.

For a dialer, use `android.intent.action.DIAL` with `tel:<number>`; it does not place
a call. `android.intent.action.CALL` places an explicitly requested call when the
platform permits it. Do not convert a request to look up or compose into a send/call.

Sources: [Contacts Provider](https://developer.android.com/identity/providers/contacts-provider),
[common Intents](https://developer.android.com/guide/components/intents-common).
