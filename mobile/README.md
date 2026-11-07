# Magizhchi MailLook for Android

The MailLook web app (`https://maillook.magizhchi.software`) in a native Android app (Capacitor), with what a
browser tab on a phone can't do well:

- **Alerts on by default, even with the app closed.** At first start the app asks once ("Allow
  notifications?") and signs the phone up with the server. New mail rings with MailLook's chime on the
  **New mail** channel; snoozed mail coming back, "nobody replied", the morning briefing and a mail that
  didn't send ring with a double chime on the **Reminders** channel, shown on top. Tapping an alert opens
  that mail. Which mail rings (Needs me / All new mail / Off) and the briefing hour are the same settings as
  on the computer, in Manage accounts. Signing out stops the alerts on that phone.
- **Attachments use the phone's own apps.** "Open" shows a PDF, photo or document in the phone's viewer;
  "Download" opens the share sheet (Drive, Files, WhatsApp, print…).
- **A mail app.** Email links in any app offer Magizhchi MailLook, and open a new mail with To, Cc, Subject
  and text filled in.
- **Links go to the browser.** Meet rooms, Box files and links in mail open in the phone's browser; MailLook
  and Magizhchi ID sign-in stay in the app.
- Its own icon and splash, an offline page that retries, and the Android back button.

Everything else is the web app, so a web deploy updates the phone app too. Only changes in this folder need
a new APK.

| What | Where |
|---|---|
| App id, the site it loads, sign-in hosts that stay inside | `capacitor.config.json` |
| Alert channels and their sounds | `android/app/src/main/java/…/MainActivity.java` |
| `MailLookNative` plugin: is Firebase set up, open alert settings | `android/app/src/main/java/…/MailLookNativePlugin.java` |
| Mail-link intent filters, Firebase defaults | `android/app/src/main/AndroidManifest.xml` |
| The sounds (`res/raw/chime.wav`, `reminder.wav`) | `make-sounds.js` (`npm run sounds`) |
| Offline page | `www/offline.html` |
| The web side (alerts, files, links, mail links) | `frontend/src/native.js` |
| Phone tokens, sending alerts | `backend/…/notify/FcmService.java`, `POST /api/push/phone` |

## Turning on phone alerts (once)

Alerts come through Firebase Cloud Messaging. The Firebase project Magizhchi Meet uses works:

1. **Firebase console** → the Meet project → Project settings → *Add app* → Android, package name
   `software.magizhchi.maillook`. Download `google-services.json` into `mobile/android/app/` and build again.
   (It isn't secret; Meet keeps its copy in git too.)
2. **Railway** → MailLook backend → Variables: `FIREBASE_SERVICE_ACCOUNT` = the service-account JSON, the
   same value Meet's backend has. The log then says "Phone alerts configured".

Until both are done the app works, and its Alerts card says alerts aren't ready yet. A build without
`google-services.json` never asks Firebase for a token, so it can't crash on it.

## Build the APK

```
cd mobile
npm install                      # first time
echo sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk > android/local.properties   # first time, forward slashes
npx cap sync android             # after changing capacitor.config.json, www/ or plugins
npm run apk                      # → android/app/build/outputs/apk/debug/MagizhchiMailLook.apk
```

The APK installs on any phone with "install unknown apps" allowed. Put it at
`https://maillook.magizhchi.software/downloads/MagizhchiMailLook.apk` for people to download. A newer APK
installs over the old one (same package, same key); raise `versionCode` and `versionName` in
`android/app/build.gradle` for each release. Debug builds are signed with this computer's debug key, so
build updates on the same computer.

For Google Play, put the upload keystore and `android/keystore.properties` (`storeFile`, `storePassword`,
`keyAlias`) beside the project, as for Meet; both are git-ignored. Then
`cd android && gradlew.bat bundleRelease`.

Icons and splash come from `docs/brand/maillook-mark-4k.png`: `npm run icons`.

## Trying it against a local MailLook

Point the app at a dev server for one build (don't commit it): in
`android/app/src/main/assets/capacitor.config.json` set `server.url` to `http://10.0.2.2:5173` (the
computer, seen from the emulator), `cleartext` to `true` and add `10.0.2.2` to `allowNavigation`; add a
`android/app/src/debug/AndroidManifest.xml` with `android:usesCleartextTraffic="true"`; build and install.
With `webContentsDebuggingEnabled` the page shows up in `chrome://inspect`. `npx cap sync` puts the real
settings back; delete the debug manifest afterwards.

## Not here yet

- **iPhone.** Building for iOS needs a Mac and an Apple developer account. Until then, iPhone users add
  MailLook to the home screen from Safari (Share → Add to Home Screen) and get alerts through Web Push.
- **Adding a Google or Microsoft mailbox** opens their sign-in in the phone's browser (Google refuses
  sign-in inside apps). The mailbox is added; switch back to the app and tap Refresh in Manage accounts. Mailboxes with an
  app password, like Hostinger, are added inside the app as usual.
