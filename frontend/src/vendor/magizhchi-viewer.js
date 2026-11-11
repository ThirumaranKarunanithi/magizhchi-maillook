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
 * A file sent by link may also carry `head`: a Blob with its first bytes (8 MB is plenty).
 * Viewers that only need a file's header, such as the database backup summary, use it when
 * they cannot read the storage link themselves.
 *
 * Options:
 *   index          which file to show first (default 0)
 *   allowDownload  false hides every download control
 *   onDownload     (file, index) => {} – your app performs the download itself (its own
 *                  permissions, decryption or native save) instead of the viewer
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

    function onMessage(e) {
      if (e.source !== frame.contentWindow || e.origin !== origin || !e.data) return;
      if (e.data.type === 'mv:ready') {
        clearTimeout(current.timer);
        current.timer = null;
        frame.contentWindow.postMessage({
          type: 'mv:open',
          files: list.map(function (f) {
            return { name: f.name, mimeType: f.mimeType || f.type, size: f.size, url: f.url, blob: f.blob || f.file, data: f.data, head: f.head };
          }),
          index: options.index || 0,
          options: {
            allowDownload: options.allowDownload !== false,
            hostDownload: typeof options.onDownload === 'function',
          },
        }, origin);
        frame.focus();
      } else if (e.data.type === 'mv:close') {
        close();
      } else if (e.data.type === 'mv:download' && typeof options.onDownload === 'function') {
        var i = e.data.index;
        if (Number.isInteger(i) && list[i]) options.onDownload(list[i], i);
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

  window.MagizhchiViewer = { open: open, close: close, version: '0.3.0' };
})();
