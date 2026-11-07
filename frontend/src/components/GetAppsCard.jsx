import { useMemo, useState } from 'react'
import qrcode from 'qrcode-generator'
import { useMail } from '../state.jsx'
import { desktop } from '../desktop.js'
import { native } from '../native.js'
import { IconDownload, IconInstall } from './Icons.jsx'

// The installers live beside the web app on Hostinger (public_html/maillook/downloads/).
export const ANDROID_APK_URL = 'https://maillook.magizhchi.software/downloads/MagizhchiMailLook.apk'
export const WINDOWS_SETUP_URL = 'https://maillook.magizhchi.software/downloads/MagizhchiMailLook-Setup.exe'

const ua = typeof navigator !== 'undefined' ? navigator.userAgent || '' : ''
export const onAndroidBrowser = /android/i.test(ua) && !native
const onWindows = /windows/i.test(ua)
const onIos = /iphone|ipad|ipod/i.test(ua) || (/macintosh/i.test(ua) && typeof navigator !== 'undefined' && navigator.maxTouchPoints > 1)

/** A QR code for a link, as an SVG: scanned with a phone's camera, it opens the link there. */
function Qr({ text, size = 112 }) {
  const svg = useMemo(() => {
    const q = qrcode(0, 'M')
    q.addData(text)
    q.make()
    return q.createSvgTag({ cellSize: 4, margin: 2, scalable: true })
  }, [text])
  // The SVG is made here from our own link, not from anything a person typed.
  return <div className="shrink-0 rounded-lg bg-white p-1" style={{ width: size, height: size }} aria-label="QR code for the Android app" role="img" dangerouslySetInnerHTML={{ __html: svg }} />
}

/** Manage accounts: MailLook for Android and Windows, wherever this isn't already the app. */
export default function GetAppsCard() {
  const { notify } = useMail()
  const [copied, setCopied] = useState(false)
  const showAndroid = !native
  const showWindows = !desktop && !native && !onAndroidBrowser && !onIos
  if (!showAndroid && !showWindows) return null

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(ANDROID_APK_URL)
      setCopied(true)
      setTimeout(() => setCopied(false), 2500)
    } catch {
      notify(ANDROID_APK_URL, 'info')
    }
  }

  return (
    <section className="card p-4" aria-label="MailLook apps">
      <div className="flex items-start gap-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-peacock-50 text-peacock-700">
          <IconDownload size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-bold">Get the MailLook apps</h2>
          <p className="mt-0.5 text-xs text-slate-500">The same mailboxes and settings everywhere, with alerts that ring even when MailLook is closed.</p>
        </div>
      </div>

      {showAndroid && (
        <div className="mt-3 flex items-start gap-3 rounded-xl border border-slate-200/70 p-3">
          {!onAndroidBrowser && <Qr text={ANDROID_APK_URL} />}
          <div className="min-w-0 flex-1 text-sm">
            <div className="font-semibold">Android</div>
            {onAndroidBrowser ? (
              <>
                <p className="mt-0.5 text-xs text-slate-500">
                  The first time, Android asks to allow installs from your browser: tap Settings, allow it, go back and tap Install.
                </p>
                <a className="btn-primary mt-2 inline-flex !py-1.5 text-sm" href={ANDROID_APK_URL} download>
                  <IconDownload size={15} /> Download for Android
                </a>
              </>
            ) : (
              <>
                <p className="mt-0.5 text-xs text-slate-500">
                  Scan the code with your Android phone’s camera, or open this link on the phone. Then tap the downloaded file to install.
                </p>
                <div className="mt-2 flex flex-wrap items-center gap-2">
                  <code className="min-w-0 truncate rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-600">maillook.magizhchi.software/downloads/MagizhchiMailLook.apk</code>
                  <button type="button" className="btn-secondary !py-1 text-xs" onClick={copy}>{copied ? 'Copied ✓' : 'Copy link'}</button>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {showWindows && (
        <div className="mt-2 flex items-center gap-3 rounded-xl border border-slate-200/70 p-3">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-600"><IconInstall size={17} /></span>
          <div className="min-w-0 flex-1 text-sm">
            <div className="font-semibold">Windows</div>
            <p className="mt-0.5 text-xs text-slate-500">Stays in the tray, rings with MailLook’s sounds, and shows the Needs me count on the taskbar.</p>
          </div>
          {onWindows ? (
            <a className="btn-secondary !py-1 text-xs" href={WINDOWS_SETUP_URL} download><IconDownload size={14} /> Download</a>
          ) : (
            <span className="text-xs text-slate-500">On a Windows PC</span>
          )}
        </div>
      )}

      {!native && !onAndroidBrowser && (
        <p className="mt-2 text-xs text-slate-500">
          iPhone and iPad: open MailLook in Safari, tap Share, then <b>Add to Home Screen</b>.
        </p>
      )}
    </section>
  )
}
