/*
 * lab-ui — controle embutido na skin: motor, skin e volume, sem sair do painel.
 *
 * Injetado depois de lab-sound.js. Um botão discreto no canto abre a lista; tudo o que
 * ele muda fica salvo (EngineApp grava o motor, o Lab grava a skin).
 */
(function () {
  'use strict';
  if (window.__labUi || !window.EngineApp) return;
  window.__labUi = true;

  var open = false, root = null, btn = null;
  var CSS = '' +
    '.labbtn{position:fixed;right:10px;bottom:10px;width:46px;height:46px;border-radius:50%;' +
    'background:rgba(20,30,42,.55);border:1px solid rgba(120,150,180,.35);color:#cfe0f0;' +
    'font:600 18px system-ui,sans-serif;opacity:.28;z-index:2147483000;cursor:pointer}' +
    '.labbtn:active{opacity:.9}' +
    '.labcard{position:fixed;right:10px;bottom:66px;width:330px;max-height:70vh;overflow:auto;' +
    'background:#0b131c;border:1px solid #22303f;border-radius:14px;padding:12px;z-index:2147483001;' +
    'color:#e6eef6;font:14px/1.4 system-ui,sans-serif;box-shadow:0 10px 30px rgba(0,0,0,.6)}' +
    '.labcard h4{margin:0 0 8px;font-size:11px;letter-spacing:.12em;text-transform:uppercase;color:#7f8c9b}' +
    '.labcard .it{display:block;width:100%;text-align:left;margin:0 0 6px;padding:9px 11px;border-radius:9px;' +
    'background:#121c26;border:1px solid #22303f;color:#e6eef6;font:14px system-ui,sans-serif;cursor:pointer}' +
    '.labcard .it[aria-pressed=true]{border-color:#38cfff;color:#38cfff}' +
    '.labcard .row{display:flex;gap:8px;align-items:center;margin:10px 0 0}' +
    '.labcard input[type=range]{flex:1;accent-color:#38cfff}';

  function style() {
    var s = document.createElement('style');
    s.textContent = CSS;
    document.head.appendChild(s);
  }

  function el(tag, attrs, text) {
    var n = document.createElement(tag);
    for (var k in attrs) if (attrs.hasOwnProperty(k)) n.setAttribute(k, attrs[k]);
    if (text != null) n.textContent = text;
    return n;
  }

  function build() {
    root = el('div', { class: 'labcard' });

    function section(title) { root.appendChild(el('h4', {}, title)); }
    function item(label, pressed, fn) {
      var b = el('button', { class: 'it', 'aria-pressed': pressed ? 'true' : 'false' }, label);
      b.addEventListener('click', function (e) { e.stopPropagation(); fn(); refresh(); });
      root.appendChild(b);
      return b;
    }

    section('Motor');
    window.EngineApp.list().forEach(function (e, i) {
      item(e.name, window.EngineApp.index() === i, function () { window.EngineApp.select(i); });
    });

    var skins = [];
    try { skins = JSON.parse(window.Lab && window.Lab.skins ? window.Lab.skins() : '[]'); } catch (e) {}
    if (skins.length > 1) {
      section('Painel');
      var cur = (/\/dash\/([a-z0-9_]+)\//.exec(location.pathname) || [])[1];
      skins.forEach(function (id) {
        item(id, id === cur, function () { window.Lab.setSkin(id); });
      });
    }

    section('Som');
    var row = el('div', { class: 'row' });
    var mute = el('button', { class: 'it', 'aria-pressed': window.EngineApp.muted() ? 'true' : 'false' },
      window.EngineApp.muted() ? 'MUDO' : 'SOM');
    mute.style.width = '86px';
    mute.style.margin = '0';
    var vol = el('input', { type: 'range', min: '0', max: '100', value: '70' });
    vol.value = String(Math.round((window.EngineApp.volume ? window.EngineApp.volume() : 0.7) * 100));
    vol.addEventListener('input', function () { window.EngineApp.setVolume(this.value / 100); });
    mute.addEventListener('click', function () { window.EngineApp.mute(!window.EngineApp.muted()); refresh(); });
    row.appendChild(vol);
    row.appendChild(mute);
    root.appendChild(row);

    if (window.Lab && window.Lab.apps) {
      var apps = [];
      try { apps = JSON.parse(window.Lab.apps()); } catch (e) {}
      if (apps.length) {
        section('Apps da central');
        var NAMES = { 'com.waze': 'WAZE', 'com.google.android.apps.maps': 'GOOGLE MAPS',
          'com.byd.androidauto': 'ANDROID AUTO', 'com.byd.carplay.ui': 'CARPLAY',
          'com.byd.mediacenter': 'MÍDIA BYD', 'com.byd.carsettings': 'AJUSTES BYD' };
        apps.forEach(function (pkg) {
          var b = el('button', { class: 'it' }, NAMES[pkg] || pkg);
          b.addEventListener('click', function (e) { e.stopPropagation(); window.Lab.openApp(pkg); });
          root.appendChild(b);
        });
      }
      if (window.Lab.navSettings) {
        var acc = window.Lab.navAccess ? window.Lab.navAccess() : false;
        var nb = el('button', { class: 'it', 'aria-pressed': acc ? 'true' : 'false' },
          acc ? 'NOTIFICAÇÕES: LIDAS' : 'LIBERAR NOTIFICAÇÕES (WAZE)');
        nb.addEventListener('click', function (e) { e.stopPropagation(); window.Lab.navSettings(); });
        root.appendChild(nb);
      }
    }

    if (window.Lab && window.Lab.probe) {
      var diag = el('button', { class: 'it', style: 'margin-top:6px' }, 'DIAGNÓSTICO DO CARRO');
      var pre = el('pre', { style: 'display:none;white-space:pre-wrap;font-size:10px;color:#8fa3b8;' +
        'max-height:180px;overflow:auto;margin:6px 0 0' });
      diag.addEventListener('click', function (e) {
        e.stopPropagation();
        var txt;
        try { txt = JSON.stringify(JSON.parse(window.Lab.probe()), null, 1); }
        catch (err) { txt = 'erro: ' + err; }
        pre.textContent = txt;
        pre.style.display = pre.style.display === 'none' ? 'block' : 'none';
      });
      root.appendChild(diag);
      root.appendChild(pre);
    }

    root.appendChild(el('div', { style: 'margin-top:10px;font-size:11px;color:#5f7183' },
      'engine/profiles.js — edite e reabra o app'));
    root.hidden = true;
    document.body.appendChild(root);

    btn = el('button', { class: 'labbtn' }, '⚙');
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      open = !open;
      root.hidden = !open;
    });
    document.body.appendChild(btn);
  }

  function refresh() {
    if (!root) return;
    var wasOpen = !root.hidden;
    root.parentNode.removeChild(root);
    build();
    root.hidden = !wasOpen;
  }

  style();
  build();
})();
