// electron-builder afterPack hook: embed the Magizhchi MailLook icon into the app .exe.
//
// We keep win.signAndEditExecutable = false so electron-builder never needs the
// winCodeSign package (its 7z archive fails to extract on Windows without Developer
// Mode / admin). Instead we stamp the icon ourselves with the self-contained `rcedit`
// tool right after the app is packed and before the NSIS installer is built — so both
// the installed .exe (Start-menu / desktop shortcut) and the installer show the logo.
const path = require('path')
const { rcedit } = require('rcedit')   // v5 is ESM with a named export

exports.default = async function afterPack(context) {
  if (context.electronPlatformName !== 'win32') return
  const exe = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.exe`)
  const icon = path.join(__dirname, 'build', 'icon.ico')
  await rcedit(exe, { icon })
  console.log(`  • stamped icon onto ${exe}`)
}
