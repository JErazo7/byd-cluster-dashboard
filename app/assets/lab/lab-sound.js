/*
 * lab-sound — liga o som do motor à skin que está sendo exibida.
 *
 * O app injeta: engine/profiles.js, engine/synth.js, engine/model.js, lab/lab-feed.js e este
 * arquivo, nesta ordem. O som arranca no primeiro toque na tela (o WebView exige gesto) e
 * segue o último estado entregue pelo feed (window.__labEv).
 *
 * Escolha do motor: window.EngineApp.select(i) / .mute(true|false) / .setVolume(0..1).
 * Fica salvo em localStorage.
 */
(function () {
  'use strict';
  if (window.__labSound || !window.ENGINES || !window.EngineSynth) return;
  window.__labSound = true;

  var engines = window.ENGINES;
  var ctx = null, voice = null, master = null, idx = 0, vol = 0.7, muted = false;
  var rpmSim = 700;   /* giro do rev livre: segue o pedal mesmo parado */
  var started = false;

  try { idx = Math.max(0, Math.min(engines.length - 1, parseInt(localStorage.getItem('evs_engine'), 10) || 0)); } catch (e) {}
  try { muted = localStorage.getItem('evs_mute') === '1'; } catch (e) {}
  try { var v0 = parseFloat(localStorage.getItem('evs_vol')); if (v0 >= 0 && v0 <= 1.5) vol = v0; } catch (e) {}

  function ensure() {
    if (ctx) return true;
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return false;
    try { ctx = new AC({ latencyHint: 'interactive' }); } catch (e) { ctx = new AC(); }
    master = ctx.createGain();
    master.gain.value = muted ? 0 : vol;
    master.connect(ctx.destination);
    return true;
  }

  function select(i) {
    if (!ensure()) return;
    idx = Math.max(0, Math.min(engines.length - 1, i | 0));
    if (voice) voice.dispose();
    voice = window.EngineSynth.create(ctx, engines[idx]);
    voice.output.connect(master);
    try { localStorage.setItem('evs_engine', String(idx)); } catch (e) {}
    if (ctx.state === 'suspended') ctx.resume();
  }

  function start() {
    if (started) return;
    started = true;
    ensure();
    select(idx);
    if (ctx && ctx.state === 'suspended') ctx.resume();
  }

  /* o WebView exige gesto do usuário; o primeiro toque em qualquer lugar serve */
  document.addEventListener('pointerdown', start, { once: false });
  document.addEventListener('touchstart', start, { once: false });
  document.addEventListener('keydown', start, { once: false });

  /* 20 Hz: segue o feed simulado (depois será o carro de verdade) */
  setInterval(function () {
    if (!voice || !window.__labEv) return;
    var ev = window.__labEv;
    var tps = (ev.tps || 0) / 100;
    var load = Math.min(1, Math.max(tps, Math.abs(ev.pw || 0) / 160));
    var eng = engines[idx] || {};
    var idle = eng.idle || 700, lim = eng.limiter || 6800;
    /* parado: o giro é o do pedal (rev livre). Andando: o do carro. */
    var target = (ev.speed < 2) ? (idle + tps * (lim - idle)) : (ev.rpm || idle);
    rpmSim += (target - rpmSim) * 0.45;          /* ~50 ms de atraso, não 300 */
    if (rpmSim < idle * 0.9) rpmSim = idle * 0.9;
    voice.update({ rpm: rpmSim, tps: tps, load: load, speed: ev.speed, on: ev.on !== false });
    /* queda brusca de potência com giro alto: estouro no escape */
    if (lastPw > 40 && ev.pw < 5 && ev.rpm > 2500) voice.pop(0.9);
    lastPw = ev.pw;
  }, 50);
  var lastPw = 0;

  window.EngineApp = {
    list: function () { return engines.map(function (e) { return { id: e.id, name: e.name }; }); },
    select: select,
    index: function () { return idx; },
    start: start,
    mute: function (m) { muted = !!m; if (master) master.gain.value = muted ? 0 : vol; try { localStorage.setItem('evs_mute', m ? '1' : '0'); } catch (e) {} },
    muted: function () { return muted; },
    volume: function () { return vol; },
    setVolume: function (v) {
      vol = Math.max(0, Math.min(1.5, v));
      if (master) master.gain.value = muted ? 0 : vol;
      try { localStorage.setItem('evs_vol', String(vol)); } catch (e) {}
    },
    state: function () { return ctx ? ctx.state : 'off'; }
  };
})();
