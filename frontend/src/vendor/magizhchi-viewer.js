/*!
 * Magizhchi Viewer – embed SDK.
 *
 * Add one script tag to any Magizhchi app, then open files with one call:
 *
 *   <script src="https://viewer.magizhchi.software/sdk/magizhchi-viewer.js"></script>
 *
 *   // A file the viewer can read by link (e.g. a short-lived Box link):
 *   MagizhchiViewer.open({ name: 'Report.pdf', url: signedUrl, mimeType: 'application/pdf' });
 *
 *   // A file your app already downloaded with its own login:
 *   MagizhchiViewer.open({ name: 'Invoice.pdf', blob });
 *
 *   // Several files with next/previous, starting on the third:
 *   MagizhchiViewer.open(files, { index: 2, allowDownload: false, onClose: () => {} });
 *
 * A long list does not have to be fetched up front. Give the files you have not loaded with
 * only their name, type and size, and pass onNeed: the viewer asks for each one when the
 * user reaches it.
 *
 *   MagizhchiViewer.open(
 *     [{ name: 'a.pdf', blob }, { name: 'b.pdf', size: 52000 }, { name: 'c.jpg', size: 9000 }],
 *     { onNeed: async (file, index) => ({ blob: await fetchMine(index) }) }   // or { url }
 *   );
 *
 * The file the viewer opens on (options.index) must be given in full. A file may carry
 * allowDownload: false to hide the download control for that file only.
 *
 * A file sent by link may also carry `head`: a Blob with its first bytes (8 MB is plenty).
 * Viewers that only need a file's header, such as the database backup summary, use it when
 * they cannot read the storage link themselves.
 *
 * Options:
 *   index          which file to show first (default 0)
 *   allowDownload  false hides every download control
 *   onDownload     (file, index) => {} – your app performs the download itself (its own
 *                  permissions, decryption or native save) instead of the viewer
 *   onNeed         (file, index) => Promise<{ blob } | { url, head? } | Blob> – load a file
 *                  that was listed without its bytes or link. Reject to show an error.
 *   onClose        () => {} – the user closed the viewer
 *   onError        (reason) => {} – the viewer did not start (site unreachable); the
 *                  overlay is already removed, so show your own fallback
 *   timeout        ms to wait for the viewer to start (default 10000)
 *
 * The viewer opens full-screen over your app in an isolated frame and removes itself
 * when the user closes it. open() returns { close() }.
 */
(function () {
  'use strict';

  var script = document.currentScript;
  var defaultBase = script && script.src ? new URL(script.src).origin : 'https://viewer.magizhchi.software';
  var current = null;

  function close() {
    if (!current) return;
    var c = current;
    current = null;
    window.removeEventListener('message', c.onMessage);
    c.frame.remove();
    document.documentElement.style.overflow = c.overflow;
    if (c.focused && c.focused.focus) c.focused.focus();
    if (c.timer) clearTimeout(c.timer);
    if (typeof c.onClose === 'function' && !c.failed) c.onClose();
  }

  function open(files, options) {
    options = options || {};
    var list = Array.isArray(files) ? files : [files];
    if (!list.length) throw new Error('MagizhchiViewer.open: no file given');
    close();

    var base = (options.base || defaultBase).replace(/\/+$/, '');
    var origin = new URL(base).origin;

    var frame = document.createElement('iframe');
    frame.src = base + '/';
    frame.title = 'Magizhchi Viewer';
    frame.allow = 'fullscreen; picture-in-picture; clipboard-write; autoplay';
    frame.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;border:0;margin:0;padding:0;' +
      'z-index:2147483000;background:#020617;color-scheme:dark';

    function wire(f) {
      return { name: f.name, mimeType: f.mimeType || f.type, size: f.size, url: f.url, blob: f.blob || f.file, data: f.data, head: f.head };
    }
    function loaded(f) { return Boolean(f.url || f.blob || f.file || f.data); }
    // sent[i] is the place in `list` of the i-th file the viewer was given.
    var sent = [];

    function onMessage(e) {
      if (e.source !== frame.contentWindow || e.origin !== origin || !e.data) return;
      if (e.data.type === 'mv:ready') {
        clearTimeout(current.timer);
        current.timer = null;
        // An older viewer cannot ask for files later: give it only the ones already loaded.
        var lazyOk = e.data.version >= 2 && typeof options.onNeed === 'function';
        sent = [];
        list.forEach(function (f, i) { if (lazyOk || loaded(f)) sent.push(i); });
        frame.contentWindow.postMessage({
          type: 'mv:open',
          files: sent.map(function (i) {
            var f = list[i];
            var w = wire(f);
            if (!loaded(f)) w.lazy = true;
            if (f.allowDownload === false) w.allowDownload = false;
            return w;
          }),
          index: Math.max(0, sent.indexOf(options.index || 0)),
          options: {
            allowDownload: options.allowDownload !== false,
            hostDownload: typeof options.onDownload === 'function',
          },
        }, origin);
        frame.focus();
      } else if (e.data.type === 'mv:close') {
        close();
      } else if (e.data.type === 'mv:download' && typeof options.onDownload === 'function') {
        var i = sent[e.data.index];
        if (Number.isInteger(i) && list[i]) options.onDownload(list[i], i);
      } else if (e.data.type === 'mv:need') {
        var at = e.data.index;
        var n = sent[at];
        var reply = function (body) {
          if (!current || current.frame !== frame) return;
          body.type = 'mv:file';
          body.index = at;
          frame.contentWindow.postMessage(body, origin);
        };
        if (!Number.isInteger(n) || !list[n] || typeof options.onNeed !== 'function') { reply({ error: 'This file could not be loaded.' }); return; }
        Promise.resolve().then(function () { return options.onNeed(list[n], n); }).then(function (got) {
          if (typeof Blob !== 'undefined' && got instanceof Blob) got = { blob: got };
          if (!got || !loaded(got)) throw new Error('This file could not be loaded.');
          var f = list[n];
          var w = wire(got);
          reply({ file: { name: got.name || f.name, mimeType: w.mimeType || f.mimeType || f.type, size: got.size != null ? got.size : f.size, url: w.url, blob: w.blob, data: w.data, head: w.head } });
        }).catch(function (err) {
          reply({ error: String((err && err.message) || err || 'This file could not be loaded.').slice(0, 300) });
        });
      }
    }

    current = {
      frame: frame,
      onMessage: onMessage,
      onClose: options.onClose,
      overflow: document.documentElement.style.overflow,
      focused: document.activeElement,
      failed: false,
      // If the viewer never answers (site down, blocked), give the app its screen back.
      timer: setTimeout(function () {
        if (!current || current.frame !== frame) return;
        current.failed = true;
        close();
        if (typeof options.onError === 'function') options.onError('timeout');
      }, options.timeout || 10000),
    };
    window.addEventListener('message', onMessage);
    document.documentElement.style.overflow = 'hidden';
    document.body.appendChild(frame);
    return { close: close };
  }

  window.MagizhchiViewer = { open: open, close: close, version: '0.4.0' };
})();
