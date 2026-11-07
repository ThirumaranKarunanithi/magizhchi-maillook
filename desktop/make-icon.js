// npm run icon: build/icon.ico (16–256 px) from icon.png, using Electron's own image scaling.
// Each size is stored as PNG inside the .ico, which Windows Vista and later read.
const { app, nativeImage } = require('electron')
const fs = require('fs')
const path = require('path')

app.whenReady().then(() => {
  const src = nativeImage.createFromPath(path.join(__dirname, 'icon.png'))
  const sizes = [16, 24, 32, 48, 64, 128, 256]
  const pngs = sizes.map((s) => src.resize({ width: s, height: s, quality: 'best' }).toPNG())
  const header = Buffer.alloc(6 + 16 * sizes.length)
  header.writeUInt16LE(0, 0)
  header.writeUInt16LE(1, 2)
  header.writeUInt16LE(sizes.length, 4)
  let offset = header.length
  sizes.forEach((s, i) => {
    const e = 6 + 16 * i
    header.writeUInt8(s >= 256 ? 0 : s, e)
    header.writeUInt8(s >= 256 ? 0 : s, e + 1)
    header.writeUInt8(0, e + 2)
    header.writeUInt8(0, e + 3)
    header.writeUInt16LE(1, e + 4)
    header.writeUInt16LE(32, e + 6)
    header.writeUInt32LE(pngs[i].length, e + 8)
    header.writeUInt32LE(offset, e + 12)
    offset += pngs[i].length
  })
  const out = path.join(__dirname, 'build', 'icon.ico')
  fs.writeFileSync(out, Buffer.concat([header, ...pngs]))
  console.log('wrote', out)
  app.quit()
})
