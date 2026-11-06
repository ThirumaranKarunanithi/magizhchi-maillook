# Magizhchi MailLook ↔ Magizhchi ID ↔ Magizhchi PA: integration test report

Date: 6 Nov 2026 · Run locally on Windows · Nothing committed (all three repos have uncommitted changes)

## What was connected

| From → To | How | Result |
|---|---|---|
| MailLook → **ID** (sign-in) | MailLook's only sign-in is "Continue with Magizhchi ID". ID returns a suite JWT; MailLook verifies it with the shared `MAGIZHCHI_JWT_SECRET` and links the MailLook user to the ID account id. | ✅ |
| MailLook → **ID** (activity) | MailLook reports "opened" to ID's `/api/v1/provision/device-seen`, so MailLook shows in the ID dashboard's apps and devices. | ✅ |
| ID → MailLook (app listing) | ID knows "Magizhchi MailLook" (sign-in screen shows the MailLook logo; dashboard tile; MailLook allowed as a return URL). | ✅ |
| MailLook → **PA** (identity) | MailLook mints a 5-minute suite token for the signed-in ID person and swaps it at PA's `/api/v1/auth/sso`. No PA password stored. | ✅ |
| MailLook → **PA** (AI) | Lane sorting, summaries and reply drafts all go to PA's new tool-free `/api/v1/assistant/complete`. MailLook needs no AI key. | ✅ |
| MailLook → **PA** (tasks, events) | "Add to PA" (task or calendar event) and "+ PA task" on summary action items. | ✅ |

## End-to-end run (real apps, real browser)

All five pieces ran together: ID backend :8090 + ID web :5180, PA backend :8086, MailLook backend :8097 + MailLook web :5177,
on Postgres 18 (separate test schemas `id_e2e`, `pa_e2e`) and a local test mailbox (GreenMail IMAP/SMTP).

| # | Step | Expected | Seen |
|---|---|---|---|
| 1 | MailLook login → "Continue with Magizhchi ID" | Goes to ID with `return=…/sso/callback&app=Magizhchi MailLook` | ✅ ID page shows "Signing in to Magizhchi MailLook" with MailLook's logo |
| 2 | Create an ID account (email code) and finish | ID sends you back into MailLook, signed in | ✅ Landed in MailLook's inbox, `/api/auth/me` = the ID email |
| 3 | Sidebar PA status | Connected, AI on | ✅ "Magizhchi PA · Connected · AI on" |
| 4 | Add a mailbox (IMAP password checked live) | 200, mail syncs | ✅ 3 emails synced |
| 5 | Lane sorting via PA's AI | Each email sorted, source "ai" | ✅ Priya → Needs me, Newsletter → Can wait, Receipt → For my info, all `laneSource: ai` |
| 6 | Summarize | Summary shown "by Magizhchi PA" | ✅ |
| 7 | Draft reply | Three drafts | ✅ "Yes, confirmed", "Not Monday", "Which room?" |
| 8 | "+ PA task" on an action item | Task created in PA | ✅ "✓ In PA" + toast |
| 9 | Add to PA → Calendar event, 30 min | Event in PA's default calendar | ✅ toast + "Open Magizhchi PA" link |
| 10 | Check PA's **own database** | Same person, task and event stored | ✅ PA user `magizhchi_account_id` = ID account `b75684fc-…`; task "Confirm Monday batch timing with Priya" with the email in its description; event 22:00–22:30 in "My Calendar" |
| 11 | Check ID's database | MailLook's activity recorded | ✅ app link + WEB session for "Magizhchi MailLook" |
| 12 | Model calls (logged at the model end) | Only from PA, with PA's key, no tools | ✅ 3 calls (lanes, summary, drafts), API key present, `tools: 0` |

### Security checks (against the live servers)

| Attack | Result |
|---|---|
| MailLook sign-in with a token signed by the wrong secret | 401 ✅ |
| PA sign-in with a token signed by the wrong secret | 401 ✅ |
| MailLook sign-in with a right-secret token but wrong issuer | 401 ✅ |
| MailLook API with no session | 401 ✅ |
| Old mailbox-password login | 401 ✅ (removed) |
| PA AI endpoint with no PA session | 401 ✅ |

### When PA goes down and comes back

| Check | Result |
|---|---|
| MailLook inbox keeps working | ✅ 200 |
| PA status shows it | ✅ "Not connected · Can't reach Magizhchi PA right now." (fixed during this test, see below) |
| Add to PA gives a clear message | ✅ "Can't reach Magizhchi PA right now…" (fixed during this test) |
| PA restarted → MailLook reconnects by itself | ✅ connected, AI on, HIGH-priority task with due date created |

## Bugs found by this test and fixed

1. **Add to PA popover cut off** by the reading pane's edge. It now opens above the page, kept on screen.
2. **PA status said "connected" while PA was down** (it trusted a cached session). It now makes a live check.
3. **Unclear error "couldn't add the task (0)"** when PA is unreachable. It now says PA can't be reached.

## Automated tests

| Suite | Result |
|---|---|
| MailLook backend (`mvn test`): full mail flow on GreenMail + ID sign-in + PA connection against a stand-in PA | **2 / 2 pass** |
| PA backend (`mvn test`), incl. new `AssistantCompletionServiceTest` | **108 / 108 pass** |
| ID backend (`mvn test`) | **18 / 18 pass** |
| MailLook web build, ID web build | ✅ both build |

## What was simulated (be aware)

- **Claude itself was a stand-in.** There's no Anthropic key on this machine, so PA pointed at a local fake of
  Anthropic's API (`ASSISTANT_BASE_URL`). Everything up to the model call is real: MailLook → PA → Anthropic SDK →
  HTTP. Answer quality from real Claude was **not** tested. First thing to check after deploy.
- **The mailbox was local (GreenMail)**, not Hostinger. Hostinger IMAP/SMTP was tested earlier with your real mailboxes.
- The ID account was a local test account (OTP mock mode, no email sent).

## To go live

1. **Same `MAGIZHCHI_JWT_SECRET` on ID, PA and MailLook.** PA's default is blank, which switches its SSO off,
   and then MailLook shows "PA doesn't have Magizhchi ID sign-in switched on".
2. **PA**: deploy the new `/api/v1/assistant/complete` and set `ANTHROPIC_API_KEY`.
3. **ID**: deploy the "Magizhchi MailLook" app registration and set `VITE_APP_URL_MAIL=<MailLook's web URL>` in
   `frontend/.env.production` (blank now). Without it, ID won't send people back to MailLook.
4. **MailLook**: set `MAILLOOK_ENCRYPTION_KEY`, `MAILLOOK_SESSION_SECRET`, `MAGIZHCHI_PA_URL`/`MAGIZHCHI_PA_WEB` if not the
   defaults, and `VITE_ID_LOGIN_URL` (`https://accounts.magizhchi.software`, already in `frontend/.env.production`).
   (`account.magizhchi.software` is ID's API, used for `MAGIZHCHI_ID_BASE_URL`.) Full steps: README → Deploy.
5. Optional: `MAGIZHCHI_ID_BASE_URL` + `MAGIZHCHI_PROVISION_SECRET` on MailLook for the ID apps-and-devices list.

## Changed files (uncommitted)

- **Magizhchi MailLook**: whole app (not yet committed at all).
- **Magizhchi-ID** (main): `AuthService.java` (known apps), `frontend/src/App.jsx`, `Dashboard.jsx`, `returnUrl.js`,
  `.env.example`, `.env.production`, `public/brand/mail-mark-sm.png`.
- **Magizhchi-PA** (main): `AssistantConfig.java`, `AssistantController.java`, `application.yml`, new
  `AssistantCompleteRequest`, `AssistantCompleteResponse`, `AssistantCompletionService` and its test.

## Test leftovers

- Postgres schemas `id_e2e` and `pa_e2e` inside `magizhchi_calendar` (your real data untouched). Remove with
  `DROP SCHEMA id_e2e CASCADE; DROP SCHEMA pa_e2e CASCADE;`
- Local test ID account `e2e-tester@magizhchi.academy` (only in `id_e2e`).
