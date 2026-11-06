# Magizhchi MailLook — API contract (Phase 1)

Base: `/api` (backend on `:8097`; Vite dev server proxies `/api`).
All JSON. Every endpoint except `POST /api/auth/sso` and `GET /api/health`
needs `Authorization: Bearer <token>`. Errors: HTTP 4xx/5xx with
`{ "error": "human readable message" }`.

Times are ISO-8601 strings (UTC). IDs are numbers.

Lanes: `needs_me` | `fyi` | `can_wait`.

---

## Auth

Sign-in is **Magizhchi ID only** (the suite's single sign-on). The UI sends people to
`{VITE_ID_LOGIN_URL}/?return={origin}/sso/callback&app=Magizhchi MailLook`; ID sends them back with
`#token=<suite JWT>`.

### POST /api/auth/sso
Exchanges a Magizhchi ID token (HS256, issuer `magizhchi-id`, signed with `MAGIZHCHI_JWT_SECRET`)
for a MailLook session. First sign-in creates the MailLook user, linked by the ID account id.
```json
// request
{ "idToken": "<JWT from Magizhchi ID>" }
// 200
{ "token": "…", "user": { "id": 1, "email": "tiru@magizhchi.academy", "name": "Tiru" } }
```
401 `{error}` for a bad signature, wrong issuer or expired token.
Mailboxes are added afterwards with `POST /api/accounts` (each with its own mailbox password).

### GET /api/auth/me → `{ id, email, name }`

---

## Accounts

`Account` object:
```json
{
  "id": 3, "email": "info@magizhchi.academy", "displayName": "Info",
  "color": "#0e7c86", "provider": "hostinger",
  "imapHost": "imap.hostinger.com", "imapPort": 993,
  "smtpHost": "smtp.hostinger.com", "smtpPort": 465,
  "status": "ok" | "syncing" | "error" | "new",
  "lastSyncAt": "2026-11-06T10:00:00Z" | null,
  "lastError": null | "Login failed",
  "unread": 12
}
```

- `GET /api/accounts` → `Account[]`
- `POST /api/accounts/detect` `{ email }` → `{ provider, imapHost, imapPort, smtpHost, smtpPort, note }`
  (provider by MX lookup: `hostinger`, `google`, `zoho`, `yahoo`, `microsoft`, `other`; `note` is a
  hint such as "Gmail needs an App Password")
- `POST /api/accounts` `{ email, password, displayName?, imapHost?, imapPort?, smtpHost?, smtpPort? }`
  → `Account` (verifies IMAP login first; 400 `{error}` if it fails). Starts a sync.
- `POST /api/accounts/bulk` `{ accounts: [{ email, password, displayName? }] }`
  → `{ results: [{ email, ok: true, account } | { email, ok: false, error }] }`
- `PATCH /api/accounts/{id}` `{ displayName?, color?, password? }` → `Account`
- `DELETE /api/accounts/{id}` → 204 (removes it from Magizhchi MailLook only; the mailbox itself is untouched)
- `POST /api/accounts/{id}/sync` → 202
- `POST /api/sync` → 202 (all accounts)

---

## Messages

`MessageSummary`:
```json
{
  "id": 501, "accountId": 3, "accountEmail": "info@magizhchi.academy", "accountColor": "#0e7c86",
  "folder": "INBOX" | "SENT" | "ARCHIVE" | "TRASH",
  "fromName": "Priya", "fromEmail": "priya@example.com",
  "to": ["info@magizhchi.academy"], "cc": [],
  "subject": "Batch timings", "snippet": "Hi, can we move…",
  "receivedAt": "…", "seen": false, "flagged": false,
  "lane": "needs_me", "laneSource": "heuristic" | "ai" | "user" | "rule",
  "hasAttachments": true, "threadKey": "…", "threadCount": 3,
  "canUnsubscribe": false
}
```

- `GET /api/messages?lane=needs_me|fyi|can_wait|all&accountId=&folder=INBOX&q=&cursor=&limit=50`
  → `{ items: MessageSummary[], nextCursor: "…" | null }`.
  Defaults: `lane=all`, `folder=INBOX`. `q` searches from, subject and body.
  Newest first.
- `GET /api/counts` →
  ```json
  {
    "lanes": { "needs_me": { "total": 9, "unread": 4 }, "fyi": {…}, "can_wait": {…} },
    "accounts": { "3": { "unread": 5 } },
    "happiness": { "score": 82, "label": "Calm" | "Busy" | "Overloaded",
                   "needsMeUnread": 4, "waitingOver48h": 1, "message": "4 people are waiting on you" }
  }
  ```
- `GET /api/messages/{id}` → `MessageSummary` plus
  `{ bodyHtml, bodyText, attachments: [{ index, name, size, contentType }], thread: MessageSummary[] }`
  (`thread` is every message with the same `threadKey`, oldest first, including this one; `bodyHtml`
  may be null; the UI must render it inside a sandboxed iframe with no scripts).
- `GET /api/messages/{id}/attachments/{index}` → file download.
- `POST /api/messages/{id}/seen` `{ seen: true }` → 204
- `POST /api/messages/{id}/flag` `{ flagged: true }` → 204
- `POST /api/messages/{id}/archive` → 204 ("Done")
- `POST /api/messages/{id}/trash` → 204 (moves to Trash, not a permanent delete)
- `POST /api/messages/{id}/lane` `{ lane: "fyi", rememberSender: true }` → 204
  (`rememberSender` makes future mail from this sender go to that lane)
- `POST /api/messages/{id}/unsubscribe` →
  `{ status: "done" | "open_url", url?: "https://…", message: "…" }`
  (`open_url`: the UI opens `url` in a new tab for the user to finish)

## AI (from Magizhchi PA; `GEMINI_API_KEY` is only a fallback; neither → 503 `{error}`)

- `GET /api/ai/status` → `{ enabled: true, provider: "pa" | "gemini" | null, message }`
- `POST /api/messages/{id}/summary` →
  `{ summary: "one line", decision: "…" | null, actions: [{ who: "You", what: "Send fee details" }], nextStep: "…" | null }`
- `POST /api/messages/{id}/drafts` →
  `{ drafts: [{ intent: "accept" | "decline" | "ask", label: "Yes, confirm", body: "plain text" }] }`
  (written in the account owner's tone, learned from their sent mail)

## Magizhchi PA

MailLook talks to PA's API as the signed-in person: it mints a 5-minute suite token for their ID
account, exchanges it at PA's `POST /api/v1/auth/sso`, and caches PA's session. No PA password.
AI calls go to PA's tool-free `POST /api/v1/assistant/complete`.

- `GET /api/pa/status` → `{ configured, connected, aiEnabled, message, webUrl }` (a live check)
- `GET /api/pa/calendars` → PA's calendars for this person
- `POST /api/messages/{id}/pa/task`
  `{ title?, dueAt?: ISO, priority?: "LOW"|"MEDIUM"|"HIGH", notes? }` →
  `{ status: "done", id, message, webUrl }`. Title defaults to the subject; the email's sender,
  subject, date, mailbox and text go into the description; label "Magizhchi MailLook".
- `POST /api/messages/{id}/pa/event`
  `{ title?, startAt: ISO (required), durationMinutes?: 5..1440 (default 30), calendarId? }` →
  same shape. Uses PA's default calendar when no `calendarId`.
- 502 `{error}` when PA can't be reached or refuses; 400 for bad input.

## Subscriptions

- `GET /api/subscriptions` →
  `[{ fromEmail, fromName, accountId, accountEmail, count, unread, lastAt, latestMessageId }]`
  (senders whose mail has a List-Unsubscribe header, most mail first). Unsubscribe uses
  `POST /api/messages/{latestMessageId}/unsubscribe`.

## Send

`POST /api/send` — `multipart/form-data`:
- part `payload` (application/json):
  `{ accountId, to: ["a@x.com"], cc: [], bcc: [], subject, text, replyToMessageId?: 501 }`
- parts `files` (0..n attachments)

→ `MessageSummary` of the stored sent copy. Sending also saves a copy into the mailbox's
Sent folder.

## Health
`GET /api/health` → `{ status: "ok" }`
