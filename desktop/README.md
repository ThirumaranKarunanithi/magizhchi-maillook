# Magizhchi MailLook for Windows

A desktop window around the MailLook web app (`https://maillook.magizhchi.software`), with the things a
browser tab can't do:

- **Stays in the tray.** Closing the window hides it, and it starts with Windows (hidden), so alerts keep
  coming all day. Quit from the tray icon or File → Quit (Ctrl+Q).
- **Alerts and a taskbar count.** The web app tells the desktop app its counts; the taskbar icon carries the
  unread "Needs me" number, and new mail rings according to the person's alert setting (Needs me / All new
  mail / Off), the same setting phones use. Clicking an alert opens Needs me.
- **The computer's mail app.** The installer registers `mailto:`. After choosing MailLook under Windows
  Settings → Default apps → MAILTO (Help → Make MailLook my mail app opens that page), mail links in any app
  open a new mail here, with To, Cc, Subject and text filled in.
- **Links open in the right place.** MailLook and the sign-in pages (Magizhchi ID, Google, Microsoft) stay in
  the window; links in mail, Meet rooms, Box files and everything else open in the default browser.
- Right-click with spelling suggestions (English), copy/paste, Ctrl+N new mail, Ctrl+1/2/3 for the lanes,
  remembered window size, an offline page that retries, and updates by itself.

There is no mail code here: everything is the web app, so a web deploy updates the desktop app too. Only
changes to these files need a new installer.

| File | What |
|---|---|
| `main.js` | Window, tray, menu, alerts, taskbar count, updates |
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
`set MAILLOOK_API_HOSTS=localhost:8097` before `npm start`.

## Make the installer

```
npm run dist:win    # → dist/MagizhchiMailLook-Setup.exe and dist/latest.yml
```

Upload **both** `MagizhchiMailLook-Setup.exe` and `latest.yml` to the `downloads/` folder of
maillook.magizhchi.software on Hostinger. The installer is then at
`https://maillook.magizhchi.software/downloads/MagizhchiMailLook-Setup.exe`, and installed apps find new
versions through `latest.yml` (they check at start and every six hours, and install on restart).
For a new version, raise `version` in `package.json` first.

The installer is a one-click, per-user install (no admin). Like Meet, it's **unsigned**
(`signAndEditExecutable: false` skips the code-signing tool, which Windows won't unpack without Developer
Mode); `afterPack.js` stamps the icon onto the .exe with `rcedit` instead. Windows SmartScreen may say
"Windows protected your PC" on first run: More info → Run anyway. A code-signing certificate removes that.

If the build fails with "app.asar … being used by another process", Windows is still scanning the last build:
build into another folder with `npx electron-builder --win nsis -c.directories.output=dist-release`.
