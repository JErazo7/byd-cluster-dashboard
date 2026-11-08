/*
 * lab-sound — liga o som do motor ao canal nativo (EngineAudio).
 *
 * O WebAudio sempre cai no stream de música; o áudio do motor sai agora por AudioTrack com
 * USAGE_ASSISTANCE_SONIFICATION, então tem volume próprio (não sobe com a música nem com o
 * GPS). O perfil continua vindo de engine/profiles.js: aqui só traduzimos para a ponte.
 *
 * Escolha do motor: window.EngineApp.select(i) / .mute(bool) / .setVolume(0..1.5).
 */
(function () {
  'use strict';
  if (window.Lab && window.Lab.log) {
    window.Lab.log('lab-sound: engines=' + (window.ENGINES ? window.ENGINES.length : -1) +
      ' lab=' + !!window.Lab + ' engineStart=' + !!(window.Lab && window.Lab.engineStart));
  }
  if (window.__labSound || !window.ENGINES || !window.Lab || !window.Lab.engineStart) return;
  window.__labSound = true;

  var engines = window.ENGINES;
  var idx = 0, vol = 1.0, muted = false, started = false;
  var rpmSim = 700, lastPw = 0;

  try { idx = Math.max(0, Math.min(engines.length - 1, parseInt(localStorage.getItem('evs_engine'), 10) || 0)); } catch (e) {}
  try { muted = localStorage.getItem('evs_mute') === '1'; } catch (e) {}
  try { var v0 = parseFloat(localStorage.getItem('evs_vol')); if (v0 >= 0 && v0 <= 1.5) vol = v0; } catch (e) {}

  function profileJson(e) {
    var h = [];
    for (var k = 1; k <= 12; k++) {
      var a = (e.harmonics && e.harmonics[k] != null) ? e.harmonics[k] : Math.pow(k, -(e.pulseSharp == null ? 1.15 : e.pulseSharp));
      h.push(Math.max(0, Math.min(1, a)));
    }
    return JSON.stringify({
      cylinders: e.cylinders || 8,
      idle: e.idle || 700,
      limiter: e.limiter || 6800,
      cut0: (e.exhaustCut && e.exhaustCut[0]) || 110,
      cut1: (e.exhaustCut && e.exhaustCut[1]) || 900,
      turbo: e.turbo || 0,
      pops: e.pops == null ? 0.4 : e.pops,
      harmonics: h
    });
  }

  function select(i) {
    idx = Math.max(0, Math.min(engines.length - 1, i | 0));
    window.Lab.engineConfig(profileJson(engines[idx]));
    try { localStorage.setItem('evs_engine', String(idx)); } catch (e) {}
    if (started) start();
  }

  function start() {
    window.Lab.engineStart();
    window.Lab.engineVolume(vol);
    window.Lab.engineMute(muted);
    started = true;
  }

  /* o WebView exige gesto; o primeiro toque serve */
  document.addEventListener('pointerdown', start, { once: false });
  document.addEventListener('touchstart', start, { once: false });

  /* 20 Hz: giro segue o pedal (rev livre parado), carga vem do pedal/potência */
  setInterval(function () {
    if (!window.__labEv) return;
    var ev = window.__labEv;
    var tps = (ev.tps || 0) / 100;
    var eng = engines[idx] || {};
    var idle = eng.idle || 700, lim = eng.limiter || 6800;
    var target = (ev.speed < 2) ? (idle + tps * (lim - idle)) : (ev.rpm || idle);
    rpmSim += (target - rpmSim) * 0.45;
    if (rpmSim < idle * 0.9) rpmSim = idle * 0.9;
    var load = Math.min(1, Math.max(tps, Math.abs(ev.pw || 0) / 160));
    /* o alvo (rpm/pedal) é calculado no Java; aqui só se a ponte nativa não existir */
    if (started && !window.Lab.engineNative) window.Lab.engineSet(rpmSim, tps, load);
  }, 50);

  window.EngineApp = {
    list: function () { return engines.map(function (e) { return { id: e.id, name: e.name }; }); },
    select: select,
    index: function () { return idx; },
    start: start,
    mute: function (m) {
      muted = !!m;
      if (window.Lab.engineMute) window.Lab.engineMute(muted);
      try { localStorage.setItem('evs_mute', m ? '1' : '0'); } catch (e) {}
    },
    muted: function () { return muted; },
    volume: function () { return vol; },
    setVolume: function (v) {
      vol = Math.max(0, Math.min(1.5, v));
      if (window.Lab.engineVolume) window.Lab.engineVolume(vol);
      try { localStorage.setItem('evs_vol', String(vol)); } catch (e) {}
    },
    state: function () { return started ? 'native' : 'off'; }
  };
})();
