/*
 * lab-ui — control embebido en la skin: motor, panel, volumen y ajustes.
 *
 * Se inyecta después de lab-sound.js. Un botón discreto en la esquina abre la tarjeta;
 * todo lo que cambia se guarda (EngineApp guarda el motor, el Lab guarda la skin).
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
    '.labcard{position:fixed;right:10px;bottom:66px;width:340px;max-height:76vh;overflow:auto;' +
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

    /* galería de paneles */
    if (window.Lab && window.Lab.openGallery) {
      var gb = el('button', { class: 'it', style: 'text-align:center;font-weight:600' }, 'PANELES (galería y vista previa)');
      gb.addEventListener('click', function (e) { e.stopPropagation(); window.Lab.openGallery(); });
      root.appendChild(gb);
    }

    /* panel del conductor */
    if (window.Lab && window.Lab.clusterToggle) {
      section('Panel del conductor');
      var on = window.Lab.clusterOn ? window.Lab.clusterOn() : true;
      var tb = el('button', { class: 'it', 'aria-pressed': on ? 'true' : 'false', style: 'text-align:center;font-weight:600' },
        on ? 'TEMA ACTIVO EN EL PANEL — tocá para apagar' : 'PANEL ORIGINAL BYD — tocá para encender el tema');
      tb.addEventListener('click', function (e) { e.stopPropagation(); window.Lab.clusterToggle(); refresh(); });
      root.appendChild(tb);
      if (window.Lab.autostartGet) {
        var asOn = window.Lab.autostartGet();
        var ab = el('button', { class: 'it', 'aria-pressed': asOn ? 'true' : 'false', style: 'margin-top:6px' },
          asOn ? 'ARRANCA SOLO — tocá para desactivar' : 'NO ARRANCA SOLO — tocá para activar');
        ab.addEventListener('click', function (e) { e.stopPropagation(); window.Lab.autostartSet(!asOn); refresh(); });
        root.appendChild(ab);
      }
    }

    /* cámara */
    if (window.Lab && window.Lab.camShape) {
      section('Cámara');
      var camOn = false;
      var cb0 = el('button', { class: 'it' }, 'CÁMARA A PANTALLA COMPLETA');
      cb0.addEventListener('click', function (e) {
        e.stopPropagation();
        camOn = !camOn;
        if (camOn) window.Lab.camShape('{"x":0,"y":90,"w":1920,"h":900}');
        else window.Lab.camHide();
        cb0.setAttribute('aria-pressed', camOn ? 'true' : 'false');
      });
      root.appendChild(cb0);
    }

    /* volante */
    if (window.Lab && window.Lab.wheel) {
      section('Volante');
      var wk = {};
      try { wk = JSON.parse(window.Lab.wheel()); } catch (e) {}
      var NAMES = { 87: 'SIGUIENTE', 88: 'ANTERIOR', 85: 'PLAY/PAUSA', 24: 'VOLUMEN +', 25: 'VOLUMEN −',
        19: 'ARRIBA', 20: 'ABAJO', 21: 'IZQUIERDA', 22: 'DERECHA', 66: 'OK', 4: 'VOLVER' };
      root.appendChild(el('div', { style: 'font-size:12px;color:#9fb0c2;margin:0 0 6px' },
        wk.key >= 0 ? ('última: ' + (NAMES[wk.key] || wk.key) + ' · ' + Math.round((wk.age || 0) / 1000) + 's') : 'ninguna tecla todavía'));
      var wacc = window.Lab.wheelAccess ? window.Lab.wheelAccess() : false;
      var wb = el('button', { class: 'it', 'aria-pressed': wacc ? 'true' : 'false' },
        wacc ? 'VOLANTE: ACTIVO' : 'ACTIVAR LECTURA DEL VOLANTE');
      wb.addEventListener('click', function (e) { e.stopPropagation(); window.Lab.wheelSettings(); });
      root.appendChild(wb);
    }

    /* apps de la central */
    if (window.Lab && window.Lab.appInfo) {
      var waze = {};
      try { waze = JSON.parse(window.Lab.appInfo('com.waze')); } catch (e) {}
      root.appendChild(el('div', { style: 'margin:4px 0 0;font-size:11px;color:' + (waze.installed ? '#35e07a' : '#7f8c9b') },
        waze.installed ? ('Waze ' + waze.version + ' · ' + (waze.installer || '?')) : 'Waze no instalado'));
    }
    if (window.Lab && window.Lab.apps) {
      var apps = [];
      try { apps = JSON.parse(window.Lab.apps()); } catch (e) {}
      if (apps.length) {
        section('Apps de la central');
        var NAMES2 = { 'com.waze': 'WAZE', 'com.google.android.apps.maps': 'GOOGLE MAPS',
          'com.byd.androidauto': 'ANDROID AUTO', 'com.byd.carplay.ui': 'CARPLAY',
          'com.byd.mediacenter': 'MULTIMEDIA BYD', 'com.byd.carsettings': 'AJUSTES BYD' };
        apps.forEach(function (pkg) {
          var b = el('button', { class: 'it' }, NAMES2[pkg] || pkg);
          b.addEventListener('click', function (e) { e.stopPropagation(); window.Lab.openApp(pkg); });
          root.appendChild(b);
        });
      }
      if (window.Lab.navSettings) {
        var acc = window.Lab.navAccess ? window.Lab.navAccess() : false;
        var nb = el('button', { class: 'it', 'aria-pressed': acc ? 'true' : 'false' },
          acc ? 'NOTIFICACIONES: LEÍDAS' : 'PERMITIR NOTIFICACIONES (WAZE)');
        nb.addEventListener('click', function (e) { e.stopPropagation(); window.Lab.navSettings(); });
        root.appendChild(nb);
      }
    }

    /* espejo de la central */
    if (window.Lab && window.Lab.mirrorStart) {
      section('Espejo');
      var live = window.Lab.mirrorLive && window.Lab.mirrorLive();
      var mb = el('button', { class: 'it', 'aria-pressed': live ? 'true' : 'false' },
        live ? 'ESPEJO ACTIVO' : 'ESPEJAR LA CENTRAL (TIEMPO REAL)');
      mb.addEventListener('click', function (e) { e.stopPropagation(); window.Lab.mirrorStart(); });
      root.appendChild(mb);
    }

    /* proyección al panel */
    if (window.Lab && window.Lab.clusterCast) {
      section('Proyección (avanzado)');
      root.appendChild(el('div', { style: 'font-size:11px;color:#7f8c9b;margin:0 0 6px;line-height:1.35' },
        'si el botón falla, por adb:\nservice call AutoContainer 2 i32 1000 i32 16 s16 ""'));
      [['ESPEJAR EN EL PANEL (COMPLETO)', 16], ['MITAD', 17], ['VOLVER AL PANEL BYD', 18]].forEach(function (r) {
        var b = el('button', { class: 'it' }, r[0]);
        b.addEventListener('click', function (e) {
          e.stopPropagation();
          var res = window.Lab.clusterCast(r[1]);
          root.appendChild(el('div', { style: 'font-size:11px;color:#9fb0c2;margin:2px 0 6px' }, res));
        });
        root.appendChild(b);
      });
    }

    /* diagnóstico */
    if (window.Lab && window.Lab.probe) {
      var diag = el('button', { class: 'it', style: 'margin-top:6px' }, 'DIAGNÓSTICO DEL COCHE');
      var pre = el('pre', { style: 'display:none;white-space:pre-wrap;font-size:10px;color:#8fa3b8;' +
        'max-height:180px;overflow:auto;margin:6px 0 0' });
      diag.addEventListener('click', function (e) {
        e.stopPropagation();
        var txt;
        try { txt = JSON.stringify(JSON.parse(window.Lab.probe()), null, 1); }
        catch (err) { txt = 'error: ' + err; }
        pre.textContent = txt;
        pre.style.display = pre.style.display === 'none' ? 'block' : 'none';
      });
      root.appendChild(diag);
      root.appendChild(pre);
    }

    /* motor */
    section('Motor');
    window.EngineApp.list().forEach(function (e, i) {
      item(e.name, window.EngineApp.index() === i, function () { window.EngineApp.select(i); });
    });

    /* paneles */
    var skins = [];
    try { skins = JSON.parse(window.Lab && window.Lab.skins ? window.Lab.skins() : '[]'); } catch (e) {}
    if (skins.length > 1) {
      section('Panel');
      var cur = (/\/dash\/([a-z0-9_]+)\//.exec(location.pathname) || [])[1];
      skins.forEach(function (id) {
        item(id, id === cur, function () { window.Lab.setSkin(id); });
      });
    }

    /* sonido */
    section('Sonido');
    var row = el('div', { class: 'row' });
    var mute = el('button', { class: 'it', 'aria-pressed': window.EngineApp.muted() ? 'true' : 'false' },
      window.EngineApp.muted() ? 'MUDO' : 'SONIDO');
    mute.style.width = '96px';
    mute.style.margin = '0';
    var vol = el('input', { type: 'range', min: '0', max: '150', value: '70' });
    vol.value = String(Math.round((window.EngineApp.volume ? window.EngineApp.volume() : 0.7) * 100));
    vol.addEventListener('input', function () { window.EngineApp.setVolume(this.value / 100); });
    mute.addEventListener('click', function () { window.EngineApp.mute(!window.EngineApp.muted()); refresh(); });
    row.appendChild(vol);
    row.appendChild(mute);
    root.appendChild(row);

    /* diario */
    if (window.EvLog) {
      section('Diario de viaje');
      var su = window.EvLog.summary();
      var line = (su.cur && su.cur.km > 0.1 ? 'en este viaje ' + su.cur.km.toFixed(1) + ' km · ' : '') +
        'total ' + su.total.km + ' km · ' + su.total.kwh + ' kWh · $ ' + su.total.cost.toFixed(2) +
        (su.total.cons ? ' · ' + su.total.cons + ' kWh/100km' : '');
      root.appendChild(el('div', { style: 'font-size:12px;color:#9fb0c2;margin:0 0 6px' }, line));
      var pr = el('input', { type: 'range', min: '20', max: '200', value: String(Math.round(window.EvLog.price() * 100)) });
      pr.addEventListener('input', function () { window.EvLog.setPrice(this.value / 100); });
      var prow = el('div', { class: 'row' });
      prow.appendChild(pr);
      prow.appendChild(el('span', { style: 'font-size:11px;color:#5f7183' }, 'precio kWh'));
      root.appendChild(prow);
      var fb = el('button', { class: 'it', style: 'margin-top:6px' }, 'CERRAR VIAJE');
      fb.addEventListener('click', function (e) { e.stopPropagation(); window.EvLog.endTrip(); refresh(); });
      root.appendChild(fb);
    }

    root.appendChild(el('div', { style: 'margin-top:10px;font-size:11px;color:#5f7183' },
      'engine/profiles.js — editá y reabrí la app'));
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

  /* teclas del volante → acciones (estándar de medios/volumen/flechas) */
  var lastCount = 0, skinsCache = [];
  function wheelPoll() {
    if (window.Lab && window.Lab.wheel) {
      var w = {};
      try { w = JSON.parse(window.Lab.wheel()); } catch (e) {}
      if (w.count > lastCount) {
        lastCount = w.count;
        var k = w.key;
        if (k === 87) window.EngineApp.select((window.EngineApp.index() + 1) % window.EngineApp.list().length);
        else if (k === 88) window.EngineApp.select((window.EngineApp.index() + window.EngineApp.list().length - 1) % window.EngineApp.list().length);
        else if (k === 85) window.EngineApp.mute(!window.EngineApp.muted());
        else if (k === 24) window.EngineApp.setVolume(Math.min(1.5, window.EngineApp.volume() + 0.1));
        else if (k === 25) window.EngineApp.setVolume(Math.max(0, window.EngineApp.volume() - 0.1));
        else if ((k === 21 || k === 22) && window.Lab.skins) {
          try { skinsCache = JSON.parse(window.Lab.skins()); } catch (e2) {}
          if (skinsCache.length > 1) {
            var cur = (/\/dash\/([a-z0-9_]+)\//.exec(location.pathname) || [])[1];
            var i = skinsCache.indexOf(cur);
            var n = (i + (k === 22 ? 1 : skinsCache.length - 1)) % skinsCache.length;
            window.Lab.setSkin(skinsCache[n]);
          }
        }
        refresh();
      }
    }
    setTimeout(wheelPoll, 350);
  }
  wheelPoll();

  style();
  build();
})();
