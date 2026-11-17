#!/usr/bin/env node
// Builds the MailLook website and publishes it to Hostinger (maillook.magizhchi.software) over FTP with TLS,
// along with the app downloads (phone APK, Windows installer, latest.yml for the desktop app's updates, latest.json).
//
//   node scripts/release.mjs find                 read only: shows the FTP folders and proves which one is the live site
//   node scripts/release.mjs site                 build the website, make the upload zip, upload the site
//   node scripts/release.mjs downloads            upload the built APK and Windows installer, then latest.json
//   node scripts/release.mjs site downloads       both; add --dry-run to only list what would go up,
//                                                 --no-build to upload the dist/ that is already built
//
// The Hostinger login is read from C:\Users\<you>\.magizhchi\hostinger-ftp.env (KEY=value lines, shared by every
// Magizhchi app, outside every git repo); environment variables of the same name win over the file:
//   HOSTINGER_FTP_USER=...
//   HOSTINGER_FTP_PASSWORD=...
// Optional, as environment variables only: HOSTINGER_FTP_DIR (the folder below), HOSTINGER_FTP_HOST (the server).
// Nothing here prints the password.
//
// Order matters so nobody ever gets a broken page or link: new hashed assets first, then the other files, then
// .htaccess and index.html last; installers first and latest.json last.
// In Git Bash, run it as MSYS_NO_PATHCONV=1 node scripts/release.mjs ... when passing /paths in the environment.
import { execFileSync, execSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Writable } from 'node:stream';
import { fileURLToPath } from 'node:url';
import { Client } from 'basic-ftp';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FRONTEND = path.join(ROOT, 'frontend');
const DIST = path.join(FRONTEND, 'dist');
const DESKTOP = path.join(ROOT, 'desktop');
const APK_DIR = path.join(ROOT, 'mobile', 'android', 'app', 'build', 'outputs', 'apk');
const OUT = path.join(ROOT, 'scripts', 'out');
const ZIP = path.join(ROOT, 'maillook-web-upload.zip');
const TAR = 'C:\\Windows\\System32\\tar.exe';

const SITE = 'https://maillook.magizhchi.software';
const HOST = '145.79.28.196';           // ftp.magizhchi.software doesn't exist
const TLS_NAME = 'hstgr.io';            // the certificate is Hostinger's *.hstgr.io
// The folder maillook.magizhchi.software serves. The login lands in /public_html, the main magizhchi.software
// site: never upload there itself. Proven with `find` before the first upload (see the README).
const DIR = '/public_html/maillook/';
const LOGIN_FILE = path.join(os.homedir(), '.magizhchi', 'hostinger-ftp.env');

const APK = 'MagizhchiMailLook.apk';
const EXE = 'MagizhchiMailLook-Setup.exe';

const args = process.argv.slice(2);
const want = (t) => args.includes(t);
const DRY = want('--dry-run');
const BUILD = !want('--no-build');
const targets = ['find', 'site', 'downloads'].filter(want);
if (targets.length === 0) {
  console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(1, 21).map((l) => l.replace(/^\/\/ ?/, '')).join('\n'));
  process.exit(1);
}

const step = (t) => console.log(`\n== ${t}`);
const sha256 = (file) => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const mb = (n) => `${(n / 1048576).toFixed(1)} MB`;
const bundleIn = (html) => /assets\/index-[^"]*\.js/.exec(html)?.[0] ?? null;
const fresh = (url) => `${url}${url.includes('?') ? '&' : '?'}nc=${Date.now()}`;

// ---- login ------------------------------------------------------------------------------------------------------

const login = () => {
  // Only the login comes from the shared file: a folder or host in it belongs to another app.
  const env = {};
  if (fs.existsSync(LOGIN_FILE))
    for (const line of fs.readFileSync(LOGIN_FILE, 'utf8').split(/\r?\n/)) {
      const m = /^\s*(HOSTINGER_FTP_USER|HOSTINGER_FTP_PASSWORD)\s*=\s*(.*?)\s*$/.exec(line);
      if (m) env[m[1]] = m[2].replace(/^(['"])(.*)\1$/, '$2');
    }
  for (const k of ['HOSTINGER_FTP_HOST', 'HOSTINGER_FTP_USER', 'HOSTINGER_FTP_PASSWORD', 'HOSTINGER_FTP_DIR'])
    if (process.env[k]) env[k] = process.env[k];
  const missing = ['HOSTINGER_FTP_USER', 'HOSTINGER_FTP_PASSWORD'].filter((k) => !env[k]);
  if (missing.length)
    throw new Error(`${missing.join(' and ')} not set. Create ${LOGIN_FILE} with HOSTINGER_FTP_USER=... and HOSTINGER_FTP_PASSWORD=... lines (see .release.env.example).`);
  let dir = env.HOSTINGER_FTP_DIR || DIR;
  if (!dir.endsWith('/')) dir += '/';
  if (!/^\/public_html\/[^/]+\//.test(dir)) throw new Error(`HOSTINGER_FTP_DIR must be an app folder inside /public_html, not ${dir}`);
  if (/^\/public_html\/[^/]*back\//i.test(dir)) throw new Error(`${dir} is a backend folder; a website never goes there`);
  return { host: env.HOSTINGER_FTP_HOST || HOST, user: env.HOSTINGER_FTP_USER, password: env.HOSTINGER_FTP_PASSWORD, dir };
};

const connect = async () => {
  const cfg = login();
  console.log(`   login: ${cfg.user} (password set, ${cfg.password.length} chars) at ${cfg.host}`);
  const client = new Client(5 * 60_000);
  await client.access({ host: cfg.host, user: cfg.user, password: cfg.password, secure: true, secureOptions: { servername: TLS_NAME } });
  return { client, dir: cfg.dir };
};

const readRemote = async (client, file) => {
  const chunks = [];
  await client.downloadTo(new Writable({ write(c, _e, cb) { chunks.push(c); cb(); } }), file);
  return Buffer.concat(chunks).toString('utf8');
};

const liveBundle = async () => bundleIn(await (await fetch(fresh(`${SITE}/`), { cache: 'no-store' })).text());

/** The folder must already be the live site: it has index.html, and that page loads what the live site loads. */
const proveSiteFolder = async (client, dir) => {
  const names = (await client.list(dir)).map((e) => e.name);
  if (!names.includes('index.html')) throw new Error(`${dir} has no index.html: refusing to upload into what may be a wrong folder`);
  const remote = bundleIn(await readRemote(client, `${dir}index.html`));
  const live = await liveBundle();
  console.log(`   ${dir}index.html loads ${remote}; ${SITE}/ loads ${live}`);
  if (!remote || remote !== live) throw new Error(`${dir} doesn't match the live site: refusing to upload`);
  return names;
};

// ---- find (read only) -------------------------------------------------------------------------------------------

const find = async () => {
  step('Find the live MailLook folder (read only)');
  const { client, dir } = await connect();
  try {
    console.log(`   pwd: ${await client.pwd()}`);
    const top = await client.list('/public_html');
    console.log(`   /public_html: ${top.map((e) => (e.isDirectory ? `${e.name}/` : e.name)).join(' ')}`);
    const names = (await client.list(dir).catch(() => [])).map((e) => e.name);
    console.log(`   ${dir}: ${names.length ? names.join(' ') : '(missing or empty)'}`);
    await proveSiteFolder(client, dir);
    console.log(`   ${dir} is the live maillook.magizhchi.software`);
  } finally {
    client.close();
  }
};

// ---- site -------------------------------------------------------------------------------------------------------

const filesUnder = (dir, base = dir) =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const full = path.join(dir, e.name);
    return e.isDirectory() ? filesUnder(full, base) : [path.relative(base, full).split(path.sep).join('/')];
  });

const buildSite = () => {
  if (BUILD) {
    step('Build the website');
    execSync('npm run build', { cwd: FRONTEND, stdio: 'inherit', env: { ...process.env, NODE_ENV: 'production' } });
  }
  for (const f of ['index.html', '.htaccess']) if (!fs.existsSync(path.join(DIST, f))) throw new Error(`frontend/dist/${f} is missing`);

  // The manual way keeps working: the same zip as always, for hPanel's File Manager.
  step('Upload zip');
  fs.rmSync(ZIP, { force: true });
  const top = fs.readdirSync(DIST).filter((f) => f !== '.htaccess');
  execFileSync(TAR, ['-a', '-c', '-f', ZIP, ...top, '.htaccess'], { cwd: DIST, stdio: 'inherit' });
  const head = Buffer.alloc(2);
  const fd = fs.openSync(ZIP, 'r');
  fs.readSync(fd, head, 0, 2, 0);
  fs.closeSync(fd);
  if (head.toString('latin1') !== 'PK') throw new Error('the upload zip is not a zip (no PK at the start)');
  fs.copyFileSync(ZIP, path.join(FRONTEND, 'maillook-web-upload.zip'));
  console.log(`   maillook-web-upload.zip (${mb(fs.statSync(ZIP).size)}), also copied to frontend/`);
};

const uploadSite = async () => {
  const local = bundleIn(fs.readFileSync(path.join(DIST, 'index.html'), 'utf8'));
  const files = filesUnder(DIST);
  const last = ['.htaccess', 'index.html'];
  const assets = files.filter((f) => f.startsWith('assets/'));
  const others = files.filter((f) => !f.startsWith('assets/') && !last.includes(f));

  step(`Upload the website (${local})`);
  let conn;
  try {
    conn = await connect();
  } catch (e) {
    if (!DRY) throw e;
    console.log(`   (not connected: ${e.message})`);
    console.log(`   would upload: ${assets.length} assets (new ones only), ${others.length} other files, then .htaccess and index.html`);
    return null;
  }
  const { client, dir } = conn;
  try {
    await proveSiteFolder(client, dir);
    const have = new Set((await client.list(`${dir}assets`).catch(() => [])).map((e) => `assets/${e.name}`));
    const newAssets = assets.filter((f) => !have.has(f));
    const order = [...newAssets, ...others, ...last];
    if (DRY) {
      console.log(`   would upload ${order.length} files (${assets.length - newAssets.length} assets already there):`);
      for (const f of order) console.log(`     ${f}`);
      return null;
    }
    const made = new Set();
    for (const f of order) {
      const sub = path.posix.dirname(f);
      if (sub !== '.' && !made.has(sub)) {
        await client.ensureDir(`${dir}${sub}`); // only ever below the proven folder
        made.add(sub);
      }
      await client.uploadFrom(path.join(DIST, ...f.split('/')), `${dir}${f}`);
    }
    console.log(`   sent ${order.length} files; skipped ${assets.length - newAssets.length} assets already there`);
    return { sent: order.length, skipped: assets.length - newAssets.length, local };
  } finally {
    client.close();
  }
};

const verifySite = async (local) => {
  step('Check the live website');
  const live = await liveBundle();
  console.log(`   live ${live}, local ${local}: ${live === local ? 'same' : 'DIFFERENT'}`);
  if (live !== local) throw new Error('the live site does not load the new build yet');
};

// ---- downloads --------------------------------------------------------------------------------------------------

const desktopVersion = () => JSON.parse(fs.readFileSync(path.join(DESKTOP, 'package.json'), 'utf8')).version;
const androidVersion = () => /versionName\s+"([^"]+)"/.exec(fs.readFileSync(path.join(ROOT, 'mobile', 'android', 'app', 'build.gradle'), 'utf8'))[1];

const builtDownloads = () => {
  const v = desktopVersion();
  const exeDir = path.join(DESKTOP, `dist-${v}`);
  const exe = path.join(exeDir, EXE);
  const yml = path.join(exeDir, 'latest.yml');
  if (!fs.existsSync(exe) || !fs.existsSync(yml))
    throw new Error(`desktop/dist-${v}/${EXE} or latest.yml is missing: build it with npx electron-builder --win nsis -c.directories.output=dist-${v}`);
  const apk = ['release', 'debug'].map((t) => path.join(APK_DIR, t, APK)).find((f) => fs.existsSync(f));
  if (!apk) throw new Error(`no ${APK} under mobile/android/app/build/outputs/apk: build it with gradlew assembleRelease (or assembleDebug)`);
  if (apk.includes(`${path.sep}debug${path.sep}`)) console.log('   note: uploading the debug APK (no release build found)');
  const describe = (file, version) => ({ version, file: path.basename(file), sizeBytes: fs.statSync(file).size, sha256: sha256(file) });
  const latest = {
    version: v,
    released: new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Kolkata' }).replace(' ', 'T') + '+05:30',
    windows: describe(exe, v),
    android: describe(apk, androidVersion()),
  };
  fs.mkdirSync(OUT, { recursive: true });
  const json = path.join(OUT, 'latest.json');
  fs.writeFileSync(json, `${JSON.stringify(latest, null, 2)}\n`);
  return { exe, yml, apk, json, latest };
};

const uploadDownloads = async () => {
  const d = builtDownloads();
  step(`Upload downloads (Windows ${d.latest.windows.version}, phone ${d.latest.android.version})`);
  let conn;
  try {
    conn = await connect();
  } catch (e) {
    if (!DRY) throw e;
    console.log(`   (not connected: ${e.message})`);
    console.log(`   would upload downloads/: ${EXE} and ${APK} when their size differs, latest.yml, then latest.json`);
    return null;
  }
  const { client, dir } = conn;
  const report = [];
  try {
    await proveSiteFolder(client, dir);
    const there = new Map((await client.list(`${dir}downloads`).catch(() => [])).map((e) => [e.name, e.size]));
    for (const f of [d.exe, d.apk]) {
      const name = path.basename(f);
      const size = fs.statSync(f).size;
      if (there.get(name) === size) {
        report.push(`downloads/${name} skipped (same size on the server)`);
        continue;
      }
      report.push(`downloads/${name} (${mb(size)})`);
      if (!DRY) await client.uploadFrom(f, `${dir}downloads/${name}`);
    }
    report.push('downloads/latest.yml', 'downloads/latest.json');
    if (!DRY) {
      await client.uploadFrom(d.yml, `${dir}downloads/latest.yml`);   // the desktop app's update check
      await client.uploadFrom(d.json, `${dir}downloads/latest.json`); // last
    }
    for (const r of report) console.log(`   ${DRY && !r.includes('skipped') ? 'would upload ' : ''}${r}`);
    return d;
  } finally {
    client.close();
  }
};

const verifyDownloads = async (d) => {
  step('Check the live downloads');
  const latest = await (await fetch(fresh(`${SITE}/downloads/latest.json`), { cache: 'no-store' })).json();
  console.log(`   latest.json: Windows ${latest.windows?.version}, phone ${latest.android?.version}`);
  if (latest.windows?.sha256 !== d.latest.windows.sha256 || latest.android?.sha256 !== d.latest.android.sha256)
    throw new Error('latest.json on the site is not the one just uploaded');
  for (const f of [d.exe, d.apk]) {
    const name = path.basename(f);
    const res = await fetch(fresh(`${SITE}/downloads/${name}`), { method: 'HEAD', cache: 'no-store' });
    const len = Number(res.headers.get('content-length'));
    console.log(`   ${name}: ${res.status}, ${mb(len)}`);
    if (res.status !== 200 || len !== fs.statSync(f).size) throw new Error(`${name} on the site is not the right file`);
  }
  const apk = Buffer.from(await (await fetch(fresh(`${SITE}/downloads/${APK}`), { cache: 'no-store' })).arrayBuffer());
  const live = createHash('sha256').update(apk).digest('hex');
  console.log(`   ${APK} sha256 ${live === d.latest.android.sha256 ? 'matches' : 'DIFFERS'}`);
  if (live !== d.latest.android.sha256) throw new Error(`${APK} on the site differs from the local one`);
};

// ---- run --------------------------------------------------------------------------------------------------------

try {
  if (want('find')) await find();
  if (want('site')) {
    buildSite();
    const r = await uploadSite();
    if (r) await verifySite(r.local);
  }
  if (want('downloads')) {
    const d = await uploadDownloads();
    if (d && !DRY) await verifyDownloads(d);
  }
  console.log(DRY ? '\nDry run: nothing was uploaded.' : '\nDone.');
} catch (e) {
  console.error(`\nStopped: ${e.message}`);
  process.exit(1);
}
