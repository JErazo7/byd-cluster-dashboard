/*
 * SharkLab — alimentação simulada das skins (código próprio, sem dependência do EvSound).
 *
 * Lê o estado do carro simulado pela ponte window.Lab (MainActivity) ~20 vezes por segundo,
 * monta um objeto plano e chama window.BrasaDriveUpdate(ev) — o mesmo contrato que as skins
 * usam: as skins existentes e as suas rodam sem alteração.
 */
(function () {
  'use strict';
  if (window.__labFeed || !window.Lab) return;
  window.__labFeed = true;

  var B = window.Lab, cars = {}, prev = '', t = 0;
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
      tout: s.tout,
      btemp: s.btemp,
      dmode: s.dmode,
      drive: s.drive || '',
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
    /* música e posição mudam devagar: uma vez por segundo */
    if (t % 20 === 0) {
      try {
        var m = JSON.parse(B.nowPlaying());
        if (m && m.title) ev.media = m;
        var p = JSON.parse(B.position());
        if (p && p.lat) ev.pos = p;
      } catch (e2) {}
    }
    t++;
    window.__labEv = ev;
    if (window.BrasaDriveUpdate) window.BrasaDriveUpdate(ev);
    requestAnimationFrame(tick);
  }

  /* uma batida por segundo, como o app faz */
  setInterval(function () { try { B.beat(0, 0, document.hidden, ''); } catch (e) {} }, 1000);
  requestAnimationFrame(tick);
})();
