# Magizhchi MailLook

> Email that makes you happy again.

One calm inbox for all your mailboxes (every `@magizhchi.academy` Hostinger mailbox, plus Gmail,
Zoho, Yahoo or any IMAP mail), sorted into three lanes: **Needs me**, **For my info** and **Can wait**.
It includes a Happiness score, AI thread summaries, AI reply drafts in your own tone, and one-tap unsubscribe.

## Layout

| Path | What |
|---|---|
| `backend/` | Spring Boot 3 (Java 17). IMAP sync and SMTP send (Jakarta MailLook), H2 or Postgres, Flyway |
| `frontend/` | React 18 + Vite + Tailwind web app |
| `docs/API.md` | The REST contract between them |
| `desktop/` | Windows app (Electron): the web app in its own window, tray, alerts, taskbar count, mailto links. See `desktop/README.md` |
| `mobile app/` | Later phase |

## Run it locally

```
cd backend  && mvn spring-boot:run          # API on :8097 (H2 file DB in backend/data, no setup)
cd frontend && npm install && npm run dev   # UI on :5177 (proxies /api)
```

Open http://localhost:5177 and press **Continue with Magizhchi ID**, the same account as Magizhchi PA,
Forms and CRM. Then go to **Manage accounts**: add a mailbox (e.g. `you@magizhchi.academy` and its
mailbox password), or use **Bulk add** to paste all your addresses, type each one's password and press **Add all**.

For local sign-in, run Magizhchi ID too (backend :8090, frontend :5180) and put
`VITE_ID_LOGIN_URL=http://localhost:5180` in `frontend/.env.local`. For AI, tasks and events, run
Magizhchi PA (:8086) with the same `MAGIZHCHI_JWT_SECRET` and `MAGIZHCHI_PA_URL=http://localhost:8086` here.

## Magizhchi suite
- **Magizhchi ID**: the only way to sign in. MailLook verifies ID's token with the shared `MAGIZHCHI_JWT_SECRET`
  and reports "MailLook was opened" back to ID (shown in the ID dashboard's apps and devices).
- **Magizhchi PA**: the AI (lane sorting, summaries, reply drafts) comes from PA's Claude, so MailLook keeps no
  AI key. **Add to PA** turns an email into a PA task or calendar event; summary action items have
  **+ PA task**. MailLook signs in to PA as the same ID person, so there's no PA password to store.

### Hostinger (`@magizhchi.academy`)
Detected automatically from the domain's MX records (`mx1/mx2.hostinger.com`):

| | Server | Port |
|---|---|---|
| IMAP | `imap.hostinger.com` | 993 (SSL) |
| SMTP | `smtp.hostinger.com` | 465 (SSL) |

The password is each mailbox's own password (hPanel → Emails → Manage → reset it there if unknown).
Hostinger doesn't save a copy of sent mail, so Magizhchi MailLook puts one in the mailbox's Sent folder.

### Other providers
Gmail and Yahoo need an **App Password**. Zoho needs IMAP turned on. Microsoft/Outlook.com accounts
have password sign-in turned off, so they will come with Outlook sign-in (OAuth) in a later phase.

## Configuration (environment variables)

| Variable | Default | Notes |
|---|---|---|
| `MAILLOOK_ENCRYPTION_KEY` | dev value | **Set in production.** Encrypts stored mailbox passwords (AES-256-GCM). If you change it, every mailbox password must be entered again. |
| `MAILLOOK_SESSION_SECRET` | dev value | **Set in production.** Signs sign-in sessions. |
| `MAGIZHCHI_JWT_SECRET` | dev value | **Set in production**, identical on ID, PA and MailLook. Verifies ID sign-in and signs MailLook's calls to PA. |
| `MAGIZHCHI_PA_URL` | `https://paback.magizhchi.software` | PA's API. Empty switches the PA connection off. |
| `MAGIZHCHI_PA_WEB` | `https://pa.magizhchi.software` | PA's web app, for "Open PA" links. |
| `MAGIZHCHI_ID_BASE_URL`, `MAGIZHCHI_PROVISION_SECRET` | (empty) | Optional. Lets MailLook report "opened" to ID's apps and devices list. |
| `GEMINI_API_KEY` | (empty) | Fallback AI, only used when PA's AI isn't available. |
| `VITE_ID_LOGIN_URL` (frontend) | `https://accounts.magizhchi.software` | Magizhchi ID's hosted sign-in page. |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | H2 file | e.g. `jdbc:postgresql://localhost:5432/magizhchi_maillook` |
| `MAILLOOK_SYNC_INTERVAL_MS` | 180000 | Background sync every 3 minutes |
| `MAILLOOK_SYNC_INITIAL_INBOX` | 300 | Newest messages fetched the first time |
| `CORS_ORIGINS` | localhost dev ports | |

## Deploy (same setup as Magizhchi ID, PA and Forms)

| Part | Where | Address |
|---|---|---|
| API (`backend/`) | Railway, from `backend/Dockerfile` | `https://maillookback.magizhchi.software` |
| Database | Railway Postgres | |
| Web app (`frontend/`) | Hostinger, static files | `https://maillook.magizhchi.software` |

**API on Railway.** New service from this repo with root directory `backend` (it finds the Dockerfile).
Add a Postgres database, then set these variables:

```
DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
DB_USER=${{Postgres.PGUSER}}
DB_PASSWORD=${{Postgres.PGPASSWORD}}
MAILLOOK_ENCRYPTION_KEY=<long random value; keep a safe copy, losing it means re-entering every mailbox password>
MAILLOOK_SESSION_SECRET=<long random value>
MAGIZHCHI_JWT_SECRET=<the same value as Magizhchi ID and PA>
MAGIZHCHI_ID_BASE_URL=https://account.magizhchi.software    # ID's API (the sign-in page is accounts.)
MAGIZHCHI_PROVISION_SECRET=<the same value as Magizhchi ID>
CORS_ORIGINS=https://maillook.magizhchi.software
```

Without `DB_URL` the API falls back to a local file database, which Railway wipes on every redeploy.

**Sharing a Postgres that already holds another app's tables?** Give MailLook its own schema. It then
creates and uses only `maillook` and never touches the other tables (tested next to another app's
`public.users`):
```
DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}?currentSchema=maillook
SPRING_FLYWAY_SCHEMAS=maillook
SPRING_FLYWAY_DEFAULT_SCHEMA=maillook
```
Never "fix" Flyway's *non-empty schema* error with `baselineOnMigrate`: MailLook would then change the other app's tables.
Then add the custom domain `maillookback.magizhchi.software` and its DNS record.

**Web app on Hostinger.** `cd frontend && npm run build` (uses `.env.production`), then upload the
**whole** `dist` folder to `maillook.magizhchi.software`, including the hidden `.htaccess`.

**Magizhchi ID.** Set `VITE_APP_URL_MAILLOOK=https://maillook.magizhchi.software` in ID's
`frontend/.env.production` and redeploy ID. Until then ID won't send people back to MailLook.

**Magizhchi PA.** Needs its `/api/v1/assistant/complete` endpoint deployed, `ANTHROPIC_API_KEY` set, and the same
`MAGIZHCHI_JWT_SECRET`.

To run the tests against Postgres instead of the in-memory database, set `MAILLOOK_TEST_DB_URL`,
`MAILLOOK_TEST_DB_USER` and `MAILLOOK_TEST_DB_PASSWORD` (one empty schema per test class).

## How sorting works
1. **Your rules**: "Move to lane → always for this sender" wins.
2. **Heuristics**: newsletters and lists go to *Can wait*; no-reply alerts, receipts and mail you're only CC'd on go to *For my info*; a person writing to you directly goes to *Needs me*.
3. **AI** (Magizhchi PA's AI): re-checks new mail in the background.

## Tests
`cd backend && mvn test` runs the whole mail flow against an in-memory IMAP/SMTP server (GreenMail),
and the PA connection against a stand-in PA (SSO exchange, AI, tasks, events, auth on every call).

## Status
Phase 1 MVP. Next: Outlook/Gmail OAuth, Tamil and Indian-language AI, Morning Brief, follow-up nudges.
