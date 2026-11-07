# Magizhchi MailLook for Windows

A desktop window around the MailLook web app (`https://maillook.magizhchi.software`), with the things a
browser tab can't do:

- **Stays in the tray.** Closing the window hides it, and it starts with Windows (hidden), so alerts keep
  coming all day. Quit from the tray icon or File → Quit (Ctrl+Q).
- **Alerts, reminders and a taskbar count.** Every half minute the app reads the server's alert feed
  (`GET /api/alerts`), the same alerts phones get by Web Push: new mail with the sender and subject (by the
  person's setting: Needs me / All new mail / Off), mail back from snooze, "nobody replied", the morning
  briefing, and a mail that didn't send. Clicking one opens that mail. Reminders and failed sends stay on
  screen until closed and flash the taskbar button. After the computer was off, what came in meanwhile
  arrives as one "While you were away". The taskbar icon carries the unread "Needs me" number.
- **MailLook's own sounds.** A chime for new mail (Chime, Soft bell or Bright) and a double chime for
  reminders, made with Web Audio in `sounds.js`. Sound on/off, volume, "Send a test alert" and "Test a
  reminder" are in the **MailLook for Windows** card. MailLook's sound plays even when Windows' Do not
  disturb is on; turn sounds off there if that matters.
- **The computer's mail app.** The installer lists MailLook as a mail app in Windows (it doesn't take over
  the one in use). After picking it next to MAILTO in Settings → Default apps (Help → Make MailLook my mail
  app opens MailLook's page there), mail links in any app open a new mail here, with To, Cc, Subject and
  text filled in.
- **Updates, your way.** "Update automatically" is on by default: a new version downloads in the background
  and installs the next time MailLook restarts. Turned off, MailLook still checks, says a new version is out,
  and downloads and installs it only when asked. The switch, "Check for updates now", "Download" and
  "Restart and update" (with download progress) are in the tray's Updates menu, in Help (press Alt), and in
  the **MailLook for Windows** card on the Manage accounts page. Clicking an "update ready" alert asks before
  restarting.
- **Links open in the right place.** MailLook and the sign-in pages (Magizhchi ID, Google, Microsoft) stay in
  the window; links in mail, Meet rooms, Box files and everything else open in the default browser.
- Right-click with spelling suggestions (English), copy/paste, Ctrl+N new mail, Ctrl+1/2/3 for the lanes,
  remembered window size, an offline page that retries, and updates by itself.

There is no mail code here: everything is the web app, so a web deploy updates the desktop app too. Only
changes to these files need a new installer.

| File | What |
|---|---|
| `main.js` | Window, tray, menu, the alert feed, taskbar count, updates and their settings |
| `alerts.js` | Which alerts show, which stay, which sound, and "While you were away" (tested) |
| `sounds.js` | The alert sounds, played in the page with Web Audio |
| `build/installer.nsh` | Closes a running MailLook before install; lists MailLook as a Windows mail app |
| `preload.js` | `window.maillookDesktop`: the only things the web app can ask for |
| `links.js` | Which links stay in the window and which go to the browser |
| `mailto.js` | `mailto:` link → To, Cc, Bcc, Subject, text |
| `offline.html` | Shown when MailLook can't be reached; retries every 30 seconds |
| `make-icon.js` | `npm run icon`: `build/icon.ico` from `icon.png` |

The web side is `frontend/src/desktop.js` (alerts, taskbar count, "open this page", "write a new mail").

## Run it from source

```
cd desktop
npm install
npm test          # links and mailto parsing
npm start         # opens https://maillook.magizhchi.software
```

Point it at a local MailLook with `set MAILLOOK_APP_URL=http://localhost:5177` and
`set MAILLOOK_API_HOSTS=localhost:8097` before `npm start`. `MAILLOOK_PROFILE=<folder>` keeps a test run's
settings apart from the real ones, and `MAILLOOK_DEBUG=1` logs each alert it shows.

## Make the installer

```
npm run dist:win    # → dist/MagizhchiMailLook-Setup.exe and dist/latest.yml
```

Upload **both** `MagizhchiMailLook-Setup.exe` and `latest.yml` to the `downloads/` folder of
maillook.magizhchi.software on Hostinger. The installer is then at
`https://maillook.magizhchi.software/downloads/MagizhchiMailLook-Setup.exe`, and installed apps find new
versions through `latest.yml` (they check at start and every six hours, and install on restart).
For a new version, raise `version` in `package.json` first.

To try an update before publishing it: build the newer version into its own folder
(`npx electron-builder --win nsis -c.directories.output=dist-feed -c.extraMetadata.version=1.0.1`), serve
that folder over http, and start an installed or `win-unpacked` copy of the older version with
`MAILLOOK_UPDATE_URL=http://localhost:8099/`. Careful: "Restart and update" really installs it.

The installer is a one-click, per-user install (no admin). Like Meet, it's **unsigned**
(`signAndEditExecutable: false` skips the code-signing tool, which Windows won't unpack without Developer
Mode); `afterPack.js` stamps the icon onto the .exe with `rcedit` instead. Windows SmartScreen may say
"Windows protected your PC" on first run: More info → Run anyway. A code-signing certificate removes that.

If the build fails with "app.asar … being used by another process", Windows is still scanning the last build:
build into another folder with `npx electron-builder --win nsis -c.directories.output=dist-release`.
