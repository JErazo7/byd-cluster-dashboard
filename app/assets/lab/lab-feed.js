/*
 * SharkLab — alimentação simulada das skins (código próprio, sem dependência do EvSound).
 *
 * Lê o estado do carro simulado pela ponte window.Lab (MainActivity) ~20 vezes por segundo,
 * monta um objeto plano e chama window.BrasaDriveUpdate(ev) — o mesmo contrato que as skins
 * usam: as skins existentes e as suas rodam sem alteração.
 */
(function () {
  'use strict';
  if (window.Lab && window.Lab.log) window.Lab.log('inj: lab-feed: injetado');
  if (window.__labFeed || !window.Lab) return;
  window.__labFeed = true;

  var B = window.Lab, cars = {}, t = 0, media = null, nav = null, pos = null, lastTs = 0;
  try { JSON.parse(B.cars()).forEach(function (c) { cars[c.id] = c; }); } catch (e) {}

  /* unidades do painel (a central da BYD manda 1 = métrico, 2 = imperial) */
  window.EvsPU = 1; window.EvsDU = 1; window.EvsSU = 1;
  window.EvsPow = function (kw) { return window.EvsPU === 2 ? kw * 1.341 : kw; };
  window.EvsPowU = function () { return window.EvsPU === 2 ? 'hp' : 'kW'; };
  window.EvsDist = function (km) { return window.EvsDU === 2 ? km / 1.609344 : km; };
  window.EvsDistU = function () { return window.EvsDU === 2 ? 'mi' : 'km'; };
  window.EvsSpd = function (kmh) { return window.EvsSU === 2 ? kmh / 1.609344 : kmh; };
  window.EvsSpdU = function () { return window.EvsSU === 2 ? 'mph' : 'km/h'; };

  var SEL = { 0: 'N', 1: 'R', 2: 'D', 3: 'P', 4: 'N' };

  function tick() {
    var nowTs = Date.now();
    var dt = lastTs ? (nowTs - lastTs) / 1000 : 0.05;
    lastTs = nowTs;
    var s;
    try { s = JSON.parse(B.state()); } catch (e) { return requestAnimationFrame(tick); }
    var c = cars[s.car] || { redline: 7000, turbo: false, name: '' };
    var on = s.phase !== 'off';
    var blink = (Date.now() % 720) < 400;
    var ev = {
      rpm: on ? Math.round(s.rpm / 10) * 10 : 0,
      speed: Math.round(s.speed),
      gear: on ? String(s.gear) : 'N',
      tps: Math.round(s.ped),
      map: 1 + Math.max(0, s.boost || 0),
      boostBar: Math.max(0, s.boost || 0),
      soc: Math.round(s.soc),
      lambda: 1,
      brake: s.brk || 0,
      turnLeft: s.turnL && blink ? 1 : 0,
      turnRight: s.turnR && blink ? 1 : 0,
      on: on,
      sel: SEL[s.gsel] || 'P',
      car: c.name || '',
      pw: Math.round(s.pw),
      odo: s.odo,
      range: s.range,
      fuel: s.fuel,
      fuelKm: s.fuelKm,
      ev: s.ev,
      tout: s.tout,
      btemp: s.btemp,
      dmode: s.dmode,
      drive: s.drive || '',
      opmode: s.opmode,        /* modo de condução: 1 NORMAL, 2 ECO, 3 SPORT */
      ecoind: s.ecoind,        /* testigo do modo: 135298 normal, 8353 eco, 10306 sport */
      surfmode: s.surfmode,    /* terreno: 1 montanha, 2 neve, 3 lama, 4 areia */
      ready: s.ready,
      regen: s.regen,
      v12: s.v12,
      phone: s.phone,
      tt: s.tt || {},
      rhd: !!s.rhd,
      warn: s.warn || [],
      adas: s.adas || {},
      tyre: s.tyre || null,
      cam: null,
      nmv: null,
      ext: s.ext || null,
      trip: s.trip || null
    };
    /* música, posição e navegação mudam devagar: lidas uma vez por segundo e
       reanexadas em TODO quadro (sem isso a manobra some no quadro seguinte) */
    if (t % 20 === 0) {
      try {
        var m = JSON.parse(B.nowPlaying());
        media = (m && m.title) ? m : null;
      } catch (e2) {}
      try {
        var p = JSON.parse(B.position());
        pos = (p && p.lat) ? p : null;
      } catch (e3) {}
      try {
        /* instrução do Waze/Maps lida da notificação (lab/NavWatch.java) */
        var nv = B.nav ? JSON.parse(B.nav()) : null;
        nav = (nv && nv.title) ? { on: true, src: nv.src, title: nv.title, text: nv.text, age: nv.age } : null;
      } catch (e4) {}
    }
    if (media) ev.media = media;
    if (pos) ev.pos = pos;
    if (nav) ev.nav = nav;
    t++;
    if (window.EvLog) ev.log = window.EvLog.update(ev, dt) ? window.EvLog.summary() : ev.log;
    window.__labEv = ev;
    if (window.BrasaDriveUpdate) window.BrasaDriveUpdate(ev);
    requestAnimationFrame(tick);
  }

  /* uma batida por segundo, como o app faz */
  setInterval(function () { try { B.beat(0, 0, document.hidden, ''); } catch (e) {} }, 1000);
  requestAnimationFrame(tick);
})();
