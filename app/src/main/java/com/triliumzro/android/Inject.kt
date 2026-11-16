package com.triliumzro.android

/**
 * Injected into the Trilium web UI (client side only, server untouched):
 *  - frosted-glass (full width, flush) top title bar and bottom launcher bar
 *  - pill shaped quick-search box
 *  - round icon buttons in the title bar / left-rounded note-panel button group
 *  - viewport patched so pinch-to-zoom works inside WebView
 *
 * Real mobile layout structure:
 *   #detail-container .title-row            -> top bar
 *   .scrolling-container (inside detail)    -> editor scroller (padding so content
 *                                              slides *under* the glass bars)
 *   #mobile-bottom-bar / #launcher-pane     -> bottom bar
 */
object Inject {

    /** Runs on every page: fullscreen / popup shims + error reporting. */
    const val SHIM = """
(function () {
  if (window.__zrqFs) return;
  window.__zrqFs = true;

  // Browser-internal warnings that look like errors but mean nothing usable
  // (ResizeObserver fires this whenever a popup changes size in the same frame).
  var IGNORED_ERR = /ResizeObserver loop|Script error\.?$|AbortError|The operation was aborted|Non-Error (promise )?rejection|^ResizeObserver/i;
  var reported = {};
  // Errors used to pop a native toast that vanished before it could be read.
  // Draw them inside the page instead: stays 12s, a tap closes it, and repeats
  // are counted (x2, x3 ...) rather than stacking up.
  var errBox = null, errTimer = null;
  function showBox(msg) {
    try {
      var host = document.body || document.documentElement;
      if (!host) return;
      if (!errBox) {
        errBox = document.createElement('div');
        errBox.id = 'zrq-err';
        errBox.setAttribute('style',
          'position:fixed;left:50%;transform:translateX(-50%);bottom:72px;z-index:2147483647;'
          + 'max-width:90vw;padding:10px 14px;border-radius:14px;background:rgba(24,24,26,.95);'
          + 'color:#ffd6d6;font:12px/1.55 -apple-system,system-ui,sans-serif;'
          + 'box-shadow:0 8px 26px rgba(0,0,0,.45);word-break:break-all;white-space:pre-wrap;');
        errBox.addEventListener('click', function () {
          errBox.style.display = 'none';
          clearTimeout(errTimer);
        });
        host.appendChild(errBox);
      }
      errBox.textContent = msg;
      errBox.style.display = 'block';
      clearTimeout(errTimer);
      errTimer = setTimeout(function () { if (errBox) errBox.style.display = 'none'; }, 12000);
    } catch (e) {}
  }
  function report(msg) {
    try {
      if (!msg) return;
      if (IGNORED_ERR.test(msg)) return;
      var entry = reported[msg];
      if (entry) {
        entry.n++;
        if (entry.n <= 10) showBox(msg + '  (x' + entry.n + ')');
        return;
      }
      reported[msg] = { n: 1 };
      showBox(msg);
      if (window.ZRQ && window.ZRQ.log) window.ZRQ.log(msg);
    } catch (e) {}
  }
  // ---- Iterator helpers polyfill ------------------------------------------
  // Trilium's bundle calls `someIterator.map(...)` (Iterator Helpers, Chrome
  // 122+). Older Android WebViews only have the iterator itself, so the call
  // throws "P.entries(...).map is not a function" and widgets such as the
  // calendar silently fail to render.
  (function () {
    var proto;
    try { proto = Object.getPrototypeOf(Object.getPrototypeOf([].values())); } catch (e) {}
    if (!proto) return;
    function def(name, fn) {
      if (proto[name]) return;
      try {
        Object.defineProperty(proto, name, { value: fn, writable: true, configurable: true });
      } catch (e) {}
    }
    function collect(iter) {
      var out = [];
      for (var v of iter) out.push(v);
      return out;
    }
    def('map', function (fn) {
      var i = 0, out = [];
      for (var v of this) out.push(fn(v, i++));
      return out;
    });
    def('filter', function (fn) {
      var i = 0, out = [];
      for (var v of this) if (fn(v, i++)) out.push(v);
      return out;
    });
    def('flatMap', function (fn) {
      var i = 0, out = [];
      for (var v of this) {
        var r = fn(v, i++);
        if (r && typeof r[Symbol.iterator] === 'function') out.push.apply(out, collect(r));
        else out.push(r);
      }
      return out;
    });
    def('take', function (n) {
      var out = [], i = 0;
      for (var v of this) { if (i++ >= n) break; out.push(v); }
      return out;
    });
    def('drop', function (n) {
      var out = [], i = 0;
      for (var v of this) { if (i++ < n) continue; out.push(v); }
      return out;
    });
    def('toArray', function () { return collect(this); });
    def('forEach', function (fn) {
      var i = 0;
      for (var v of this) fn(v, i++);
    });
    def('some', function (fn) {
      var i = 0;
      for (var v of this) if (fn(v, i++)) return true;
      return false;
    });
    def('every', function (fn) {
      var i = 0;
      for (var v of this) if (!fn(v, i++)) return false;
      return true;
    });
    def('find', function (fn) {
      var i = 0;
      for (var v of this) if (fn(v, i++)) return v;
      return undefined;
    });
    def('reduce', function () {
      var fn = arguments[0], acc = arguments[1], i = 0, started = arguments.length > 1;
      for (var v of this) {
        if (!started) { acc = v; started = true; i++; continue; }
        acc = fn(acc, v, i++);
      }
      return acc;
    });
  })();

  // ---- more APIs an older WebView is missing -------------------------------
  // Trilium 0.107 uses a handful of newer built-ins. Each one only throws when
  // the matching action is used ("x.toReversed is not a function"), so they
  // showed up as random errors while everything else kept working.
  (function () {
    function def(obj, name, fn) {
      if (!obj) return;
      try {
        if (typeof obj[name] !== 'function') {
          Object.defineProperty(obj, name, { value: fn, writable: true, configurable: true });
        }
      } catch (e) {}
    }
    var AP = Array.prototype, SP = String.prototype;
    function toInt(v) { return v === undefined ? 0 : (Math.trunc ? Math.trunc(v) : parseInt(v, 10) || 0); }
    var slice = AP.slice;
    def(AP, 'at', function (i) {
      i = toInt(i); if (i < 0) i += this.length;
      return (i >= 0 && i < this.length) ? this[i] : undefined;
    });
    def(SP, 'at', function (i) {
      i = toInt(i); if (i < 0) i += this.length;
      return (i >= 0 && i < this.length) ? this.charAt(i) : undefined;
    });
    def(AP, 'findLast', function (fn, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) if (fn.call(thisArg, this[i], i, this)) return this[i];
      return undefined;
    });
    def(AP, 'findLastIndex', function (fn, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) if (fn.call(thisArg, this[i], i, this)) return i;
      return -1;
    });
    def(AP, 'toReversed', function () {
      var out = [], n = this.length;
      for (var i = 0; i < n; i++) out[i] = this[n - 1 - i];
      return out;
    });
    def(AP, 'toSorted', function (cmp) { return slice.call(this).sort(cmp); });
    def(AP, 'toSpliced', function (start, del) {
      var a = slice.call(this), rest = slice.call(arguments, 2);
      AP.splice.apply(a, [start, del].concat(rest));
      return a;
    });
    def(AP, 'with', function (i, v) {
      var a = slice.call(this); i = toInt(i); if (i < 0) i += a.length;
      if (i < 0 || i >= a.length) throw new RangeError('Invalid index');
      a[i] = v;
      return a;
    });
    def(SP, 'replaceAll', function (find, rep) {
      var s = String(this);
      if (find instanceof RegExp) {
        if (!find.global) throw new TypeError('replaceAll must be called with a global RegExp');
        return s.replace(find, rep);
      }
      var f = String(find);
      if (typeof rep === 'function') {
        var out = '', i = 0, at;
        while (f && (at = s.indexOf(f, i)) >= 0) {
          out += s.slice(i, at) + String(rep(f, at, s));
          i = at + f.length;
        }
        return out + s.slice(i);
      }
      return f ? s.split(f).join(String(rep)) : s;
    });
    def(Object, 'hasOwn', function (o, k) {
      return o != null && Object.prototype.hasOwnProperty.call(Object(o), k);
    });
    def(Object, 'groupBy', function (items, fn) {
      var out = Object.create(null);
      var i = 0;
      for (var v of items) {
        var k = fn(v, i++);
        (out[k] || (out[k] = [])).push(v);
      }
      return out;
    });
    def(Promise, 'withResolvers', function () {
      var r, j;
      var p = new Promise(function (res, rej) { r = res; j = rej; });
      return { promise: p, resolve: r, reject: j };
    });
    if (typeof Promise.any !== 'function') {
      Promise.any = function (list) {
        return new Promise(function (resolve, reject) {
          var errs = [], left = 0, done = false;
          for (var v of list) {
            left++;
            Promise.resolve(v).then(function (x) {
              if (!done) { done = true; resolve(x); }
            }, function (e) {
              errs.push(e);
              if (--left === 0 && !done) {
                var err = new Error('All promises were rejected');
                err.name = 'AggregateError';
                err.errors = errs;
                reject(err);
              }
            });
          }
          if (left === 0) reject(new Error('All promises were rejected'));
        });
      };
    }
    if (typeof window.structuredClone !== 'function') {
      window.structuredClone = function (v) { return JSON.parse(JSON.stringify(v)); };
    }
    // Set algebra (Chrome 122+). The editor calls .intersection() while typing,
    // which surfaced as "Promise错误: ...intersection is not a function".
    function asSet(v) {
      if (v instanceof Set) return v;
      var out = new Set();
      if (v && typeof v[Symbol.iterator] === 'function') {
        for (var x of v) out.add(x);
      }
      return out;
    }
    var SP2 = Set.prototype;
    def(SP2, 'union', function (other) {
      var out = new Set(this), o = asSet(other);
      o.forEach(function (v) { out.add(v); });
      return out;
    });
    def(SP2, 'intersection', function (other) {
      var out = new Set(), o = asSet(other);
      this.forEach(function (v) { if (o.has(v)) out.add(v); });
      return out;
    });
    def(SP2, 'difference', function (other) {
      var out = new Set(), o = asSet(other);
      this.forEach(function (v) { if (!o.has(v)) out.add(v); });
      return out;
    });
    def(SP2, 'symmetricDifference', function (other) {
      var o = asSet(other), out = new Set();
      this.forEach(function (v) { if (!o.has(v)) out.add(v); });
      o.forEach(function (v) { if (!this.has(v)) out.add(v); }, this);
      return out;
    });
    def(SP2, 'isSubsetOf', function (other) {
      var o = asSet(other), ok = true;
      this.forEach(function (v) { if (!o.has(v)) ok = false; });
      return ok;
    });
    def(SP2, 'isSupersetOf', function (other) {
      var ok = true;
      asSet(other).forEach(function (v) { if (!this.has(v)) ok = false; }, this);
      return ok;
    });
    def(SP2, 'isDisjointFrom', function (other) {
      var o = asSet(other), ok = true;
      this.forEach(function (v) { if (o.has(v)) ok = false; });
      return ok;
    });
  })();

  // ---- our own "About" page, reached from the ☰ menu ----------------------
  // The ☰ menu gets one extra entry under the official "About Trilium Notes"
  // item. Choosing it opens the official dialog and rewrites its content in
  // place, so the styling, icon, panel colours and theme behaviour are the
  // app's own. Nothing here touches the server: browsers and other clients
  // still see stock Trilium.
  var ZRO_TITLE = 'TriliumZro';
  var ZRO_REPO = 'https://github.com/rp168/TriliumZro';
  var ZRO_AUTHOR = 'https://github.com/rp168';
  var ZRO_LICENSE = 'https://www.gnu.org/licenses/agpl-3.0.html';
  var zroTries = 0;

  function zroAttr(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function zroRows() {
    var ver = '' + (window.__ZRQ_APP_VER || '');
    var code = '' + (window.__ZRQ_APP_CODE || '');
    return ''
      + '<dl><dt>客户端版本:</dt><dd>v' + zroAttr(ver)
      + (code ? ' (build ' + zroAttr(code) + ')' : '') + '</dd></dl>'
      + '<dl><dt>基于:</dt><dd id="zrq-zro-server">TriliumNext</dd></dl>'
      + '<dl><dt>开发者:</dt><dd><a class="tn-link" href="' + ZRO_AUTHOR
      + '" target="_blank" rel="noopener noreferrer">rp168</a></dd></dl>'
      + '<dl><dt>说明:</dt><dd>非官方客户端，仅做移动端界面适配；'
      + '不改动服务器代码与数据，客户端不保存笔记数据。</dd></dl>';
  }

  function zroFill() {
    var d = document.querySelector('.about-dialog');
    if (!d) return false;
    var content = d.querySelector('.about-dialog-content');
    var h2 = content ? content.querySelector('h2') : null;
    if (!h2) return false;
    var sheetNow = content.querySelector('.property-sheet');
    if (h2.textContent === ZRO_TITLE && sheetNow
        && sheetNow.querySelector('#zrq-zro-server')) {
      return true;
    }

    h2.textContent = ZRO_TITLE;

    var link = content.querySelector('a.tn-link');
    if (link) {
      link.href = ZRO_REPO;
      link.textContent = 'github.com/rp168/TriliumZro';
    }

    var sheet = content.querySelector('.property-sheet');
    if (sheet) sheet.innerHTML = zroRows();

    var foot = d.querySelector('footer');
    if (foot) {
      var links = foot.querySelectorAll('a');
      if (links[0]) {
        links[0].href = ZRO_REPO;
      }
      if (links[1]) {
        links[1].href = ZRO_LICENSE;
      }
      // the official page has a third "Donate" link; ours keeps two
      for (var i = links.length - 1; i >= 2; i--) {
        if (links[i].parentNode) links[i].parentNode.removeChild(links[i]);
      }
    }
    d.setAttribute('data-zro', '1');

    try {
      fetch('/api/app-info', { credentials: 'same-origin' })
        .then(function (r) { return r.json(); })
        .then(function (j) {
          var el = document.getElementById('zrq-zro-server');
          if (!el || !j || !j.appVersion) return;
          el.textContent = 'TriliumNext ' + j.appVersion
            + '（database ' + (j.dbVersion || '?') + ', sync ' + (j.syncVersion || '?') + '）';
        })
        .catch(function () {});
    } catch (e) {}
    return true;
  }

  function zroClose() {
    var d = document.querySelector('.about-dialog[data-zro="1"]');
    var x = d ? d.querySelector('.btn-close') : null;
    if (x) x.click();
    try { if (window.ZRQ && window.ZRQ.setAbout) window.ZRQ.setAbout(false); } catch (e) {}
  }
  window.__zrqCloseAbout = zroClose;

  // the ☰ menu is rebuilt on every open, so the entry is re-inserted each time
  function zroMenu() {
    var menus = document.querySelectorAll('.tn-menu-scroll');
    for (var i = 0; i < menus.length; i++) {
      var m = menus[i];
      if (m.querySelector('#zrq-zro-menu')) continue;
      var items = m.querySelectorAll('.dropdown-item');
      var official = null;
      for (var k = 0; k < items.length; k++) {
        if (/About Trilium|关于\s*Trilium/i.test(items[k].innerText || '')) {
          official = items[k];
          break;
        }
      }
      if (!official) continue;
      var li = document.createElement('li');
      li.id = 'zrq-zro-menu';
      li.setAttribute('role', 'menuitem');
      li.className = 'dropdown-item';
      li.innerHTML = '<span><span class="bx bx-user-circle tn-icon"></span>'
        + '<span class="tn-menu-gap"></span>关于 TriliumZro 客户端</span>';
      official.parentNode.insertBefore(li, official.nextSibling);
    }
  }

  var aboutTimer = null;
  function scheduleAbout() {
    if (aboutTimer) return;
    aboutTimer = setTimeout(function () {
      aboutTimer = null;
      zroMenu();
      // the dialog can be closed by the app at any time: keep the host flag
      // in sync so the back key does not try to close something that is gone
      var dlg = document.querySelector('.about-dialog');
      if (!dlg) {
        try { if (window.ZRQ && window.ZRQ.setAbout) window.ZRQ.setAbout(false); } catch (e) {}
      } else {
        // the app re-renders the dialog on each open: drop our marker when the
        // content is the stock one again, so the back key does not hijack it
        var h = dlg.querySelector('h2');
        if (h && h.textContent.trim() !== ZRO_TITLE) dlg.removeAttribute('data-zro');
      }
    }, 150);
  }

  document.addEventListener('click', function (e) {
    var t = e.target;
    var hit = (t && t.closest) ? t.closest('#zrq-zro-menu') : null;
    if (!hit) return;
    e.preventDefault();
    e.stopPropagation();

    // let the app open its own About dialog, then rewrite it in place
    var menus = document.querySelectorAll('.tn-menu-scroll .dropdown-item');
    var official = null;
    for (var i = 0; i < menus.length; i++) {
      if (/About Trilium|关于\s*Trilium/i.test(menus[i].innerText || '')) {
        official = menus[i];
        break;
      }
    }
    if (official) {
      official.click();
    } else {
      zroFill();
    }
    zroTries = 0;
    (function tick() {
      if (zroFill() || zroTries++ > 12) return;
      setTimeout(tick, 150);
    })();
    try { if (window.ZRQ && window.ZRQ.setAbout) window.ZRQ.setAbout(true); } catch (e) {}
  }, true);

  try {
    new MutationObserver(scheduleAbout).observe(document, { childList: true, subtree: true });
  } catch (e) {}
  document.addEventListener('DOMContentLoaded', scheduleAbout);
  window.addEventListener('load', scheduleAbout);
  scheduleAbout();

  window.addEventListener('error', function (e) {
    var m = (e && e.message) || '';
    var f = (e && e.filename) || '';
    report('JS错误: ' + m + (f ? ' @' + f.split('/').pop() : ''));
  });
  window.addEventListener('unhandledrejection', function (e) {
    var r = e && e.reason;
    report('Promise错误: ' + ((r && (r.message || r)) || 'unknown'));
  });

  // ---- Fullscreen API shim -------------------------------------------------
  // WebView reports requestFullscreen() but only really enters fullscreen when
  // the host implements onShowCustomView; several ROMs ignore it silently, so
  // Trilium's zen mode / fullscreen button looked dead. We emulate it with CSS
  // and fire the same events the app listens for.
  var fsEl = null, fsBackup = null;
  var FS_PROPS = {
    'position': 'fixed', 'top': '0px', 'left': '0px', 'right': '0px', 'bottom': '0px',
    'width': '100vw', 'height': '100vh', 'margin': '0px', 'padding': '0px',
    'border-radius': '0px', 'z-index': '2147483000', 'overflow': 'auto'
  };
  function fsDispatch() {
    try { document.dispatchEvent(new Event('fullscreenchange')); } catch (e) {}
    try { document.dispatchEvent(new Event('webkitfullscreenchange')); } catch (e) {}
  }
  function fsEnter(el) {
    if (fsEl) fsExit();
    if (!el) return;
    fsEl = el;
    el.classList.add('zrq-fullscreen');
    try {
      fsBackup = {};
      for (var k in FS_PROPS) {
        if (!FS_PROPS.hasOwnProperty(k)) continue;
        fsBackup[k] = el.style.getPropertyValue(k);
        fsBackup[k + '!prio'] = el.style.getPropertyPriority(k);
        el.style.setProperty(k, FS_PROPS[k], 'important');
      }
    } catch (e) {}
    try { if (window.ZRQ && window.ZRQ.fs) window.ZRQ.fs(true); } catch (e) {}
    fsDispatch();
  }
  function fsExit() {
    if (!fsEl) return;
    try {
      fsEl.classList.remove('zrq-fullscreen');
      if (fsBackup) {
        for (var k in FS_PROPS) {
          if (!FS_PROPS.hasOwnProperty(k)) continue;
          var v = fsBackup[k], p = fsBackup[k + '!prio'];
          if (v) fsEl.style.setProperty(k, v, p || '');
          else fsEl.style.removeProperty(k);
        }
        fsBackup = null;
      }
    } catch (e) {}
    fsEl = null;
    try { if (window.ZRQ && window.ZRQ.fs) window.ZRQ.fs(false); } catch (e) {}
    fsDispatch();
  }
  try {
    Object.defineProperty(document, 'fullscreenElement', {
      configurable: true, get: function () { return fsEl; }
    });
    Object.defineProperty(document, 'webkitFullscreenElement', {
      configurable: true, get: function () { return fsEl; }
    });
    Object.defineProperty(document, 'fullscreenEnabled', {
      configurable: true, get: function () { return true; }
    });
    Object.defineProperty(document, 'webkitFullscreenEnabled', {
      configurable: true, get: function () { return true; }
    });
  } catch (e) {}
  var ep = Element.prototype;
  ep.requestFullscreen = function () { fsEnter(this); return Promise.resolve(); };
  ep.webkitRequestFullscreen = function () { fsEnter(this); };
  ep.webkitRequestFullScreen = function () { fsEnter(this); };
  ep.mozRequestFullScreen = function () { fsEnter(this); };
  document.exitFullscreen = function () { fsExit(); return Promise.resolve(); };
  document.webkitExitFullscreen = function () { fsExit(); };
  document.webkitCancelFullScreen = function () { fsExit(); };
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' || e.keyCode === 27) fsExit();
  }, true);

  // ---- window.open fallback ----------------------------------------------
  // If a popup cannot be created, navigate in place instead of dying silently.
  var nativeOpen = window.open;
  window.open = function (url) {
    var w = null;
    try { w = nativeOpen.apply(window, arguments); } catch (e) {}
    if (!w && url) {
      try { window.location.href = url; } catch (e) {}
    }
    return w;
  };

  // ---- stuck overlay / dropdown rescue ------------------------------------
  // Trilium dims the page with .global-menu-cover while a dropdown (calendar,
  // search, ...) is open; tapping the cover closes it. On some WebView builds
  // that close never happens and the whole page stays dimmed and dead.
  // Watchdog: if the cover is still there after a tap, close it ourselves.
  function closeDropdowns() {
    var covers = document.querySelectorAll('.global-menu-cover');
    for (var i = 0; i < covers.length; i++) {
      var c = covers[i];
      try { c.classList.remove('show'); } catch (e) {}
    }
    // let Trilium's own handler run first
    try {
      document.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true
      }));
    } catch (e) {}
    setTimeout(function () {
      var left = document.querySelectorAll('.global-menu-cover');
      for (var j = 0; j < left.length; j++) {
        try { left[j].remove(); } catch (e) {}
      }
      var portals = document.querySelectorAll('.tn-dropdown-portal');
      for (var k = 0; k < portals.length; k++) {
        try {
          var shown = portals[k].querySelectorAll('.dropdown-menu.show, .show');
          for (var m = 0; m < shown.length; m++) shown[m].classList.remove('show');
        } catch (e) {}
      }
      document.body.classList.remove('modal-open');
      document.body.style.removeProperty('overflow');
      report('已自动解除卡住的遮罩');
    }, 600);
  }

  document.addEventListener('pointerdown', function (e) {
    var t = e.target;
    while (t) {
      var cn = t.className;
      if (cn && ('' + cn).indexOf('global-menu-cover') >= 0) {
        setTimeout(function () {
          if (document.querySelector('.global-menu-cover')) closeDropdowns();
        }, 450);
        return;
      }
      t = t.parentElement;
    }
  }, true);

  // If a dropdown opened but its panel failed to render (0 height, or nothing
  // visible inside), the page just stays dimmed forever. Detect and undo that.
  function big(r) { return !!r && r.width > 40 && r.height > 40; }
  function panelOk() {
    var sites = document.querySelectorAll('.tn-dropdown-portal');
    for (var i = 0; i < sites.length; i++) {
      var p = sites[i];
      if (big(p.getBoundingClientRect())) return true;
      var kids = p.querySelectorAll('*');
      for (var k = 0; k < kids.length; k++) {
        if (big(kids[k].getBoundingClientRect())) return true;
      }
    }
    return false;
  }
  try {
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var added = records[i].addedNodes || [];
        for (var a = 0; a < added.length; a++) {
          var n = added[a];
          if (!n || n.nodeType !== 1 || !n.className) continue;
          if (('' + n.className).indexOf('global-menu-cover') < 0) continue;
          setTimeout(function () {
            if (!document.querySelector('.global-menu-cover')) return;
            if (panelOk()) return;
            report('下拉面板没有渲染出来，已自动关闭');
            closeDropdowns();
          }, 1500);
        }
      }
    }).observe(document.body, { childList: true });
  } catch (e) {}

  // ---- options dialog: keep card corners off the scrollbar lane ------------
  // The dialog's scroll boxes reserve a scrollbar gutter on BOTH edges, so in
  // the WebView the painted box ends one bar-width short of the layout box and
  // the last pixels of a card's right edge (its rounded corner) are cut away.
  // Chrome uses overlay scrollbars, takes no space, and never shows it.
  // Trilium sets the gutter with its own !important rule, which a stylesheet of
  // equal weight cannot outrank; inline declarations can. Options has several
  // sub-pages inside the same dialog, each with its own scroller, so every
  // descendant is checked, not just whichever pane rendered first.
  function zrqPin(el) {
    try {
      var g = '';
      try { g = getComputedStyle(el).scrollbarGutter || ''; } catch (e) {}
      if (g && g.indexOf('stable') === 0) {
        el.style.setProperty('scrollbar-gutter', 'auto', 'important');
        return true;
      }
      if (el.scrollHeight > el.clientHeight + 2
          || el.scrollWidth > el.clientWidth + 2) {
        el.style.setProperty('scrollbar-gutter', 'auto', 'important');
        return true;
      }
    } catch (e) {}
    return false;
  }
  function zrqPinDialog() {
    try {
      var dlg = document.querySelector('.options-dialog');
      if (!dlg) return;
      // the page subtracts a bar width from both insets, which only held while
      // a start-edge gutter was reserved; keep the start inset whole
      if (dlg.style.getPropertyValue('--options-pane-inset-left') !==
          'var(--options-inset-left)') {
        dlg.style.setProperty('--options-pane-inset-left',
          'var(--options-inset-left)', 'important');
      }
      var all = dlg.getElementsByTagName('*');
      for (var i = 0; i < all.length; i++) {
        if (all[i].getAttribute('data-zrq-gc') === '1') continue;
        if (zrqPin(all[i])) all[i].setAttribute('data-zrq-gc', '1');
      }
    } catch (e) {}
  }
  var zrqQueued = false;
  function zrqSchedule() {
    if (zrqQueued) return;
    zrqQueued = true;
    try {
      requestAnimationFrame(function () { zrqQueued = false; zrqPinDialog(); });
    } catch (e) { zrqQueued = false; zrqPinDialog(); }
  }
  try {
    new MutationObserver(zrqSchedule)
      .observe(document.documentElement || document, { childList: true, subtree: true });
  } catch (e) {}
  zrqSchedule();

})();
"""

    /** Cosmetic layer: frosted bars, pill search, colours. */
    const val STYLES = """
(function () {
  if (window.__zrqGlass) { window.__zrqGlass.refresh(); return; }

  var GLASS_BG = 'background:rgba(128,128,128,0.06)!important;'
    + 'background:color-mix(in srgb, var(--main-background-color) 64%, transparent)!important;';

  var GLASS_BLUR = '-webkit-backdrop-filter:blur(20px) saturate(180%)!important;'
    + 'backdrop-filter:blur(20px) saturate(180%)!important;';

  var CSS = ''
    // palette / shared values
    + ':root{'
    +   '--zrq-block:color-mix(in srgb, var(--main-text-color) 16%, transparent);'
    +   '--zrq-blue:#1E9BF7;'
    +   '--zrq-green:#34C759;'
    + '}'
    // ---- top bar: floats over the content, transparent at rest
    + '#detail-container .title-row{'
    +   'position:absolute!important;z-index:60!important;'
    +   'top:0!important;left:0!important;right:0!important;width:auto!important;'
    +   'height:50px!important;border-radius:0!important;'
    +   'padding:0 6px!important;margin:0!important;overflow:visible!important;'
    +   'align-items:center!important;'
    +   'background:transparent!important;'
    +   'border:0!important;box-shadow:none!important;'
    +   'transition:background-color .2s ease;'
    + '}'
    // frosted only once the content has scrolled under it: Trilium itself
    // toggles `hide-title` exactly then, so no JS state of our own is needed
    + '#detail-container .title-row:not(.hide-title){'
    +   GLASS_BG + GLASS_BLUR
    +   'box-shadow:inset 0 -1px 0 color-mix(in srgb, var(--main-text-color) 9%, transparent)!important;'
    + '}'
    // ---- make room so content really scrolls under the bars ----
    + '#detail-container .scrolling-container{'
    +   'padding-top:52px!important;padding-bottom:58px!important;'
    +   'scrollbar-width:none!important;-ms-overflow-style:none!important;'
    + '}'
    + '#detail-container .scrolling-container::-webkit-scrollbar{'
    +   'width:0!important;height:0!important;display:none!important;'
    + '}'
    // our own thin indicator: it starts under the title bar, never above it
    + '#zrq-sb{'
    +   'position:fixed!important;right:2px!important;width:4px!important;'
    +   'border-radius:2px!important;background:rgba(128,128,128,.65)!important;'
    +   'opacity:0;pointer-events:none!important;z-index:2147482000!important;'
    +   'transition:opacity .35s ease;'
    + '}'
    // the tree panel is laid out in the full split, so clamp it to the bar
    + '.mobile-note-navigator{'
    +   'max-height:calc(100% - 104px)!important;'
    + '}'
    // ---- bottom bar: full width, flush, frosted ----
    + '#mobile-bottom-bar{'
    +   'position:fixed!important;left:0!important;right:0!important;bottom:0!important;'
    +   'width:auto!important;z-index:60!important;border-radius:0!important;'
    +   'padding-bottom:env(safe-area-inset-bottom)!important;'
    +   GLASS_BG + GLASS_BLUR
    +   'border:0!important;'
    +   'box-shadow:inset 0 1px 0 color-mix(in srgb, var(--main-text-color) 9%, transparent)!important;'
    + '}'
    + '#launcher-pane{background:transparent!important;}'
    // ---- bottom bar: rightmost menu key ----
    + '#mobile-bottom-bar .global-menu-button{'
    +   'background:var(--zrq-blue)!important;color:#fff!important;'
    // square corners: the rounded key reads as a floating button, while the
    // bar it sits in is a rectangle
    +   'border-radius:0!important;'
    + '}'
    + '#mobile-bottom-bar .global-menu.open .global-menu-button,'
    + '#mobile-bottom-bar .global-menu-button[aria-expanded="true"],'
    + '#mobile-bottom-bar .global-menu-button.active,'
    + '#mobile-bottom-bar .global-menu-button:active{'
    +   'background:var(--zrq-green)!important;'
    + '}'
    // ---- while editing the formatting toolbar is parked exactly where the bar
    //      lives, which used to force the bar out of its frosted state. Instead
    //      of un-frosting the bar, the toolbar is lifted by the bar height, so
    //      the bar keeps its glass and the toolbar floats just above it.
    // `--zrq-edit-lift` is the height of the bottom bar WHILE IT IS ON SCREEN.
    // With the keyboard open the app hides the bar, the variable drops to 0 and
    // both the toolbar and the content padding follow it down -- otherwise the
    // lift stayed at 54px and left an empty row under the formatting toolbar.
    + 'body.zrq-edit .classic-toolbar-outer-container{'
    +   'transform:translateY(calc(-1 * var(--zrq-edit-lift,0px)))!important;'
    + '}'
    + 'body.zrq-edit #mobile-bottom-bar{padding-bottom:0!important;}'
    + 'body.zrq-edit #detail-container .scrolling-container{'
    +   'padding-bottom:calc(var(--zrq-edit-lift,0px) + 46px)!important;'
    + '}'
    // ---- editing toolbar: "hide keyboard" key ----
    // ---- emulated fullscreen (see the Fullscreen API shim) ----
    // ---- modals / dropdowns: never leave them stuck mid-transition ----
    // Some WebViews do not run CSS transitions reliably; Bootstrap then keeps
    // the dialog at opacity 0 while its backdrop is already blocking input
    // (page dims, nothing is clickable).
    + '.modal-backdrop.fade{transition:none!important;}'
    + '.modal.fade{transition:none!important;}'
    + '.modal.fade.show{opacity:1!important;}'
    + '.zrq-fullscreen{'
    +   'position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;'
    +   'width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;'
    +   'border-radius:0!important;z-index:2147483000!important;'
    +   'background:var(--main-background-color,#111)!important;overflow:auto!important;'
    + '}'
    + '.hide-keyboard-action,'
    + '.hide-keyboard-action button,'
    + '.classic-toolbar-island .hide-keyboard-action .icon-action{'
    +   'background:var(--zrq-blue)!important;color:#fff!important;'
    + '}'
    // ---- search box -> pill ----
    + '.quick-search-box,.search-string-editor,.quick-search-box .cm-editor{'
    +   'border-radius:999px!important;'
    + '}'
    + '.quick-search-box{padding-left:14px!important;padding-right:6px!important;}'
    // ---- title bar icon buttons : round ----
    + '#detail-container .title-row .icon-action{border-radius:50%!important;}'
    // ---- right cluster (note panel + "...") : ONE continuous block,
    //      left end half round, right end square and flush with the screen edge ----
    + '#detail-container .title-row > .mobile-note-panel-button .icon-action,'
    + '#detail-container .title-row > .component:last-child .icon-action,'
    + '#detail-container .title-row > .component:last-child button{'
    +   'background:transparent!important;border-radius:50%!important;box-shadow:none!important;'
    + '}'
    // ---- the two right-hand icons must sit in the middle of their block:
    //      centre the glyph itself instead of trusting the font's line box
    + '#detail-container .title-row > .mobile-note-panel-button .icon-action,'
    + '#detail-container .title-row > .component:last-child .icon-action{'
    +   'display:flex!important;align-items:center!important;'
    +   'justify-content:center!important;line-height:1!important;'
    + '}'
    // ---- title inside the bar (after scrolling) must stay transparent ----
    + '#detail-container .title-row .note-title,'
    + '#detail-container .title-row .note-title-widget,'
    + '#detail-container .title-row .note-title-widget input{'
    +   'background:transparent!important;box-shadow:none!important;border:0!important;'
    + '}'
    // ---- zen mode: the bars must really disappear, plus a way back out ----
    + 'body.zen #mobile-bottom-bar{display:none!important;}'
    + 'body.zen #detail-container .scrolling-container{'
    +   'padding-top:0!important;padding-bottom:0!important;'
    + '}'
    // ---- bottom-sheet panels opened from the bars must cover the page ----
    + '.zrq-fullsheet{'
    +   'top:48px!important;bottom:0!important;height:auto!important;'
    +   'max-height:none!important;'
    + '}'
    // the sheet must never paint at its floating-ui spot before we size it
    + '.tn-popup[class*="mobile-bottom-"]:not(.zrq-fullsheet):not(.zrq-keep-sheet){'
    +   'visibility:hidden!important;'
    + '}'
    // dialogs opened from the bars: fill the screen like the native app
    + '.modal.show .modal-dialog{'
    +   'margin:0!important;width:100%!important;max-width:100%!important;'
    +   'height:100%!important;max-height:100%!important;border-radius:0!important;'
    + '}'
    + '.modal.show .modal-content{'
    +   'height:100%!important;border-radius:0!important;'
    + '}'
    + '.global-menu-cover{'
    +   'top:0!important;bottom:0!important;height:100%!important;'
    + '}'
    // Fullscreen exit is a small green pill sitting ABOVE the bottom-bar menu
    // key: the top-right cluster is reserved for the note panel + menu and any
    // state badge that shows up there (protected / syncing), so a floating
    // icon there would eventually land on top of one. Above the menu key it
    // stays on the same side as the control it replaces and touches nothing.
    + '.zrq-fs-exit{'
    +   'position:fixed!important;left:auto!important;top:auto!important;'
    +   'right:12px!important;bottom:70px!important;'
    +   'width:auto!important;height:auto!important;'
    +   'padding:7px 14px!important;border-radius:999px!important;'
    +   'background:var(--zrq-green,#34C759)!important;color:#fff!important;'
    +   'font:600 13px/1 -apple-system,system-ui,sans-serif!important;'
    +   'letter-spacing:.02em!important;white-space:nowrap!important;'
    +   'z-index:2147482500!important;display:flex!important;'
    +   'align-items:center!important;justify-content:center!important;'
    +   'box-shadow:0 4px 14px rgba(0,0,0,.28)!important;'
    +   'cursor:pointer!important;border:0!important;'
    + '}'
    + '.zrq-fs-exit:active{transform:scale(.94);}'
    + '.zrq-zen-exit{'
    +   'position:fixed!important;right:12px!important;bottom:16px!important;'
    +   'z-index:2147482000!important;display:flex!important;'
    +   'align-items:center!important;gap:6px!important;'
    +   'padding:8px 14px!important;border-radius:999px!important;'
    +   'font:600 13px/1 -apple-system,system-ui,sans-serif!important;'
    +   'color:#fff!important;background:rgba(10,132,255,.88)!important;'
    +   'box-shadow:0 4px 14px rgba(0,0,0,.28)!important;'
    +   'cursor:pointer!important;'
    + '}'
    // ---- if Trilium adds a scrolled state with solid background, keep the glass ----
    + '#detail-container .title-row.scrolled,'
    + '#detail-container .title-row.is-scrolled,'
    + '#detail-container .title-row[data-scrolled]{'
    +   GLASS_BG + GLASS_BLUR
    + '}'
    // ---- fallback for any other fixed/sticky bar on this page ----
    + '.zrq-bar{'
    +   GLASS_BG + GLASS_BLUR
    + '}'
    // ---- Trilium ships body#trilium-app.backdrop-effects-disabled * { backdrop-filter:none!important }
    //      which outranks plain id selectors; match it with higher specificity.
    + 'body#trilium-app.backdrop-effects-disabled #detail-container .title-row:not(.hide-title),'
    + 'body#trilium-app.backdrop-effects-disabled #mobile-bottom-bar,'
    + 'body#trilium-app.backdrop-effects-disabled .zrq-bar{'
    +   GLASS_BLUR
    + '}';

  function paint() {
    var st = document.getElementById('zrq-glass-style');
    if (!st) {
      st = document.createElement('style');
      st.id = 'zrq-glass-style';
      (document.head || document.documentElement).appendChild(st);
    }
    if (st.textContent !== CSS) st.textContent = CSS;
    // (do NOT move the node around: moving it on every pass re-triggers the
    //  mutation observer and, with the calendar open, freezes the WebView)
  }

  function fixViewport() {
    var m = document.querySelector('meta[name="viewport"]');
    if (!m) {
      m = document.createElement('meta');
      m.setAttribute('name', 'viewport');
      (document.head || document.documentElement).appendChild(m);
    }
    var c = m.getAttribute('content') || '';
    if (c.indexOf('maximum-scale') >= 0 || c.indexOf('user-scalable') >= 0 ||
        c.indexOf('width=device-width') < 0) {
      m.setAttribute('content', 'width=device-width, initial-scale=1, viewport-fit=cover');
    }
  }

  // fallback: any other full-width fixed/sticky bar gets the glass treatment too
  // Right cluster (panel button + "...") painted as ONE block behind the icons.
  // Nothing in the layout is touched: a decorative layer is positioned from the
  // measured rect of the back button and of the panel button, so the block is
  // always the same colour / height / vertical position as the back button and
  // always runs to the right edge of the screen with a square corner.
  function syncCluster() {
    var rows = document.querySelectorAll('#detail-container .title-row');
    var tr = null;
    for (var i = 0; i < rows.length; i++) {
      var rr = rows[i].getBoundingClientRect();
      if (rr.width > 0 && rr.height > 0) { tr = rows[i]; break; }
    }
    if (!tr) return;

    var panel = tr.querySelector('.mobile-note-panel-button');
    if (!panel || panel.getBoundingClientRect().width < 4) {
      var dead = tr.querySelector('.zrq-cluster');
      if (dead) dead.style.display = 'none';
      return;
    }

    var backWrap = tr.querySelector('.toggle-sidebar-button');
    var backBtn = backWrap
      ? (backWrap.querySelector('.icon-action') || backWrap.querySelector('button') || backWrap)
      : null;
    if (!backBtn) return;

    var trR = tr.getBoundingClientRect();
    var bkR = backBtn.getBoundingClientRect();
    var pnR = panel.getBoundingClientRect();
    if (bkR.height < 10 || pnR.width < 4) return;

    // None of the three title-bar icons keeps a chip fill: the bar's own
    // frosted surface is the background, so the glyphs sit straight on it and
    // nothing merges with a state badge that appears next to them.
    backBtn.style.setProperty('background', 'transparent', 'important');
    backBtn.style.setProperty('box-shadow', 'none', 'important');
    if (backWrap && backWrap !== backBtn) {
      backWrap.style.setProperty('background', 'transparent', 'important');
    }

    var layer = tr.querySelector('.zrq-cluster');
    if (!layer) {
      layer = document.createElement('div');
      layer.className = 'zrq-cluster';
      tr.insertBefore(layer, tr.firstChild);
    }
    var st = layer.style;
    st.display = 'block';
    st.position = 'absolute';
    st.pointerEvents = 'none';
    st.zIndex = '-1';
    st.background = 'transparent';
    st.borderRadius = '999px 0 0 999px';
    st.right = '0';
    // Leave room for the pill's left cap, but never let the cap swallow the
    // state badge that appears to its left: when one shows up, stop 6px short
    // of it so the two blocks stay visibly separate instead of merging.
    var leftPx = pnR.left - trR.left - 8;
    var sib = panel.previousElementSibling;
    while (sib) {
      var sr = sib.getBoundingClientRect();
      if (sr.width > 4 && sr.height > 4) {
        var want = sr.right - trR.left + 6;
        if (want > leftPx) leftPx = want;
        break;
      }
      sib = sib.previousElementSibling;
    }
    st.left = Math.round(leftPx) + 'px';
    st.top = Math.round(bkR.top - trR.top) + 'px';
    st.height = Math.round(bkR.height) + 'px';
    st.width = 'auto';

    // icons must not paint their own block on top of it
    var kids = panel.querySelectorAll('.icon-action, button');
    for (var j = 0; j < kids.length; j++) {
      kids[j].style.setProperty('background', 'transparent', 'important');
      kids[j].style.setProperty('box-shadow', 'none', 'important');
    }
    var last = panel.nextElementSibling;
    if (last) {
      var lk = last.querySelectorAll('.icon-action, button');
      for (var k = 0; k < lk.length; k++) {
        lk[k].style.setProperty('background', 'transparent', 'important');
        lk[k].style.setProperty('box-shadow', 'none', 'important');
      }
    }
  }

  function detectExtra() {
    var vw = window.innerWidth;
    var nodes = document.querySelectorAll('header,footer,nav,[class*="toolbar"],[class*="top-bar"],[class*="bottom-bar"],[class*="app-bar"]');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.classList.contains('zrq-bar')) continue;
      if (el.closest('.title-row')) continue;
      if (el.id === 'mobile-bottom-bar') continue;
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
      var r = el.getBoundingClientRect();
      if (r.width < vw * 0.7 || r.height < 28 || r.height > 190) continue;
      if (el.closest('[role="dialog"], .modal, .tn-popup')) continue;
      el.classList.add('zrq-bar');
    }
  }

  // editing = the formatting toolbar (or the soft keyboard bar) is on screen.
  // In that state the bottom bar must stay in the app's own flow, otherwise our
  // fixed glass bar covers the editing toolbar.
  function syncEditingState() {
    // The formatting toolbar stays in the DOM all the time, but it is really
    // shown only while editing: Trilium flips visibility/opacity on it.
    var editing = false;
    var islands = document.querySelectorAll('.classic-toolbar-island');
    for (var i = 0; i < islands.length; i++) {
      var el = islands[i];
      var cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none') continue;
      if (cs.opacity === '0' || cs.opacity === '0.0') continue;
      var r = el.getBoundingClientRect();
      if (r.width > 20 && r.height > 10) { editing = true; break; }
    }
    if (!editing) {
      var kbBtn = document.querySelector('.hide-keyboard-action');
      if (kbBtn) {
        var ks = getComputedStyle(kbBtn);
        var kr = kbBtn.getBoundingClientRect();
        if (ks.visibility !== 'hidden' && ks.opacity !== '0' && kr.height > 4 && kr.width > 4) {
          editing = true;
        }
      }
    }
    var bar = document.getElementById('mobile-bottom-bar');
    var lift = 0;
    if (bar) {
      var br = bar.getBoundingClientRect();
      var bst = getComputedStyle(bar);
      if (br.height > 10 && bst.display !== 'none' && bst.visibility !== 'hidden') {
        lift = Math.round(br.height);
      }
    }
    if (document.body) {
      if (editing) {
        // always write it, including 0: a stale 54px used to survive the
        // keyboard opening and show up as a blank row under the toolbar
        document.body.style.setProperty('--zrq-edit-lift', lift + 'px');
        if (!document.body.classList.contains('zrq-edit')) document.body.classList.add('zrq-edit');
      } else if (document.body.classList.contains('zrq-edit')) {
        document.body.classList.remove('zrq-edit');
      }
    }
  }

  // ---- work scheduling -------------------------------------------------
  // Calendar / big widgets add thousands of nodes at once. Running the whole
  // pass on every mutation froze the WebView, so: debounce, throttle, and
  // ignore the mutations this script itself produces.
  var timer = null, running = false, extraDirty = true;
  var timerUrgent = false, timerAt = 0;

  // ---- thin scroll indicator, drawn only under the title bar -----------------
  var sbarHide = null;
  function syncScrollbar() {
    var sc = null;
    var all = document.querySelectorAll('#detail-container .scrolling-container');
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (e.clientHeight > 100 && e.scrollHeight > e.clientHeight + 2) { sc = e; break; }
    }
    var sb = document.getElementById('zrq-sb');
    if (!sc) {
      if (sb) sb.style.opacity = '0';
      return;
    }
    if (!sb) {
      sb = document.createElement('div');
      sb.id = 'zrq-sb';
      document.body.appendChild(sb);
    }
    var bar = document.getElementById('mobile-bottom-bar');
    var top = 50;
    var bottom = window.innerHeight;
    if (bar) {
      var br = bar.getBoundingClientRect();
      if (br.height > 10 && br.top > 100) bottom = Math.round(br.top);
    }
    var track = Math.max(60, bottom - top);
    var h = Math.max(30, Math.round(track * sc.clientHeight / sc.scrollHeight));
    var max = sc.scrollHeight - sc.clientHeight;
    var ratio = max > 0 ? Math.min(1, Math.max(0, sc.scrollTop / max)) : 0;
    sb.style.top = Math.round(top + ratio * (track - h)) + 'px';
    sb.style.height = h + 'px';
    sb.style.opacity = '1';
    if (sbarHide) clearTimeout(sbarHide);
    sbarHide = setTimeout(function () {
      var el = document.getElementById('zrq-sb');
      if (el) el.style.opacity = '0';
    }, 900);
  }

  // ---- make bar-opened sheets cover the whole page ------------------------
  function syncSheets() {
    var sheets = document.querySelectorAll('.tn-popup.mobile-bottom, .tn-popup[class*="mobile-bottom-"]');
    for (var i = 0; i < sheets.length; i++) {
      var el = sheets[i];
      if (!el.classList.contains('show')) continue;
      if (el.querySelector('.calendar-dropdown-widget')) {
        el.classList.add('zrq-keep-sheet');
        continue;
      }
      // floating-ui writes an inline top/height (sometimes with !important),
      // so the sheet has to be overridden inline as well, not just by class
      el.classList.add('zrq-fullsheet');
      var st = el.style;
      st.setProperty('top', '48px', 'important');
      st.setProperty('bottom', '0px', 'important');
      st.setProperty('height', 'auto', 'important');
      st.setProperty('max-height', 'none', 'important');
    }
  }

  // ---- fullscreen: exit icon in the bottom-left corner ----------------------
  function syncFsExit() {
    var on = !!document.fullscreenElement;
    var btn = document.getElementById('zrq-fs-exit');
    if (on && !btn) {
      btn = document.createElement('div');
      btn.id = 'zrq-fs-exit';
      btn.className = 'zrq-fs-exit';
      btn.textContent = '退出全屏';
      btn.addEventListener('click', function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        try { document.exitFullscreen(); } catch (e) {}
        try { if (window.ZRQ && window.ZRQ.fs) window.ZRQ.fs(false); } catch (e) {}
        syncFsExit();
      }, true);
      document.body.appendChild(btn);
    } else if (!on && btn) {
      try { btn.remove(); } catch (e) {}
    }
    if (!on || !btn) return;
    // Park the pill directly above the bottom bar (i.e. above the menu key).
    // Right side only, so the top-right icon cluster is never touched.
    // fall back to the bar's usual height: in zen / fullscreen the bar can be
    // hidden, and the pill must still sit above where the menu key would be
    // rather than dropping onto the zen exit pill at the bottom edge
    var barH = 58;
    var bar = document.getElementById('mobile-bottom-bar');
    if (bar) {
      var br = bar.getBoundingClientRect();
      if (br.height > 4) barH = Math.max(0, window.innerHeight - br.top);
    }
    btn.style.setProperty('bottom', Math.round(barH + 12) + 'px', 'important');
  }

  // ---- zen mode exit pill ------------------------------------------------
  function syncZen() {
    var on = document.body && document.body.classList.contains('zen');
    var btn = document.getElementById('zrq-zen-exit');
    if (on && !btn) {
      btn = document.createElement('div');
      btn.id = 'zrq-zen-exit';
      btn.className = 'zrq-zen-exit';
      btn.textContent = '退出禅模式';
      btn.addEventListener('click', function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        try {
          document.body.classList.remove('zen');
          document.dispatchEvent(new CustomEvent('zenModeChanged', { detail: { isEnabled: false } }));
        } catch (e) {}
        syncZen();
      }, true);
      document.body.appendChild(btn);
    } else if (!on && btn) {
      try { btn.remove(); } catch (e) {}
    }
  }

  // ---- a little scroll headroom ----------------
  // Trilium drops `hide-title` (and our frosted bar appears) only after the note
  // has been scrolled a bit. A short note has no overflow at all, so it could
  // never be swiped up and the big inline title stayed put. An invisible spacer
  // at the end of the note gives every note at least ~180px of travel.
  var HEADROOM = 180;
  function syncHeadroom() {
    if (!document.body) return;
    var sc = null;
    var all = document.querySelectorAll('#detail-container .scrolling-container');
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (e.getBoundingClientRect().width > 100 && e.clientHeight > 100) { sc = e; break; }
    }
    var sp = document.getElementById('zrq-headroom');
    if (!sc || document.body.classList.contains('zrq-edit')) {
      if (sp && sp.parentNode) sp.parentNode.removeChild(sp);
      return;
    }
    if (!sp || sp.parentNode !== sc) {
      if (sp && sp.parentNode) sp.parentNode.removeChild(sp);
      sp = document.createElement('div');
      sp.id = 'zrq-headroom';
      sp.className = 'zrq-headroom';
      sp.setAttribute('aria-hidden', 'true');
      sp.style.cssText = 'height:0;width:100%;flex:0 0 auto;pointer-events:none;';
      sc.appendChild(sp);
    }
    if (sp.style.height !== '400px') sp.style.height = '400px';
    // scrollHeight is floored at clientHeight, so the only reliable way to
    // learn how much room the content already gives is to measure with a known
    // spacer in place, then correct it.
    var max = sc.scrollHeight - sc.clientHeight;
    var h = Math.round(400 + (HEADROOM - max));
    if (!(h > 0)) h = 0;
    if (h > 900) h = 900;
    sp.style.height = h + 'px';
  }

  function run() {
    if (running) return;
    running = true;
    try {
      try { fixViewport(); } catch (e) {}
      try { paint(); } catch (e) {}
      try { syncCluster(); } catch (e) {}
      try { syncEditingState(); } catch (e) {}
      try { syncZen(); } catch (e) {}
      try { syncFsExit(); } catch (e) {}
      try { syncSheets(); } catch (e) {}
      try { syncScrollbar(); } catch (e) {}
      try { syncHeadroom(); } catch (e) {}
      if (extraDirty) {
        extraDirty = false;
        try { detectExtra(); } catch (e) {}
      }
    } finally {
      running = false;
    }
  }

  // Wait until the DOM has been quiet for a while before doing any work:
  // big widgets (calendar, search) insert thousands of nodes in bursts and
  // running during that burst is what made the WebView hang.
  function refresh(urgent) {
    var wait = urgent ? 120 : 550;
    // never let a stream of mutations keep postponing the pass: a pending
    // timer always wins, so the work happens within `wait` of the first event
    if (timer) {
      if (!urgent || timerUrgent) return;
      if (Date.now() - timerAt >= wait) return;
      clearTimeout(timer);
      timer = null;
    }
    timerUrgent = !!urgent;
    timerAt = Date.now();
    timer = setTimeout(function () {
      timer = null;
      timerUrgent = false;
      try { run(); } catch (e) {}
    }, wait);
  }

  function isOurs(n) {
    if (!n || n.nodeType !== 1) return false;
    return n.id === 'zrq-glass-style' ||
           (n.className && ('' + n.className).indexOf('zrq-') >= 0);
  }

  function onlyOwnMutations(records) {
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      if (isOurs(r.target)) continue;
      var added = r.addedNodes || [], removed = r.removedNodes || [];
      var foreign = false;
      for (var a = 0; a < added.length; a++) if (!isOurs(added[a])) foreign = true;
      for (var b = 0; b < removed.length; b++) if (!isOurs(removed[b])) foreign = true;
      if (foreign || r.type === 'characterData' ||
          (r.type === 'attributes' && !isOurs(r.target))) return false;
    }
    return true;
  }

  function boot() {
    refresh(true);
    try {
      new MutationObserver(function (records) {
        if (onlyOwnMutations(records)) return;
        refresh();
      }).observe(document.documentElement, { childList: true, subtree: true, characterData: true });
    } catch (e) {}
    // Themes may replace stylesheets or mutate body classes / CSS variables.
    // Wait for external CSS to load too; insertion alone can sample old colours.
    document.addEventListener('load', function (ev) {
      if (ev.target && ev.target.tagName === 'LINK') refresh(true);
    }, true);
    document.addEventListener('transitionend', function (ev) {
      if (ev.target && ev.target.closest && ev.target.closest('.toggle-sidebar-button')) refresh(true);
    }, true);
    document.addEventListener('visibilitychange', function () { refresh(true); });
    document.addEventListener('zenModeChanged', function () { refresh(true); });
    document.addEventListener('scroll', function () {
      try { syncScrollbar(); } catch (e) {}
    }, true);
    try {
      new MutationObserver(function (records) {
        for (var i = 0; i < records.length; i++) {
          var t = records[i].target;
          if (t === document.documentElement || t === document.body ||
              (t.closest && t.closest('head, .toggle-sidebar-button'))) {
            refresh(true); break;
          }
        }
      }).observe(document.documentElement, {
        attributes: true, subtree: true, attributeFilter: ['class', 'style', 'href', 'disabled', 'media', 'data-theme', 'data-bs-theme']
      });
    } catch (e) {}
    window.addEventListener('hashchange', function () { extraDirty = true; refresh(true); });
    window.addEventListener('resize', function () { refresh(true); });
    window.addEventListener('orientationchange', function () { refresh(true); });
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', function () { refresh(true); });
    }
  }

  // Sheets are positioned by floating-ui right after insertion; fix their
  // geometry in the same task so they never flash at the bottom first.
  try {
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var added = records[i].addedNodes || [];
        for (var a = 0; a < added.length; a++) {
          var n = added[a];
          if (!n || n.nodeType !== 1) continue;
          var cn = '' + (n.className || '');
          if (cn.indexOf('mobile-bottom') < 0) continue;
          try { syncSheets(); } catch (e) {}
        }
      }
    }).observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}

  window.__zrqGlass = { refresh: refresh };
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }
})();
"""

}
