/*
 * lab-logbook — diário de bordo: cada viagem fica registrada sozinha.
 *
 * Código próprio, sem servidor: guarda em localStorage (WebView da central) e exporta JSON.
 * Entra energia e sai energia pelo mesmo cano (potência do carro), então o número fecha com
 * o que o painel mostra.
 *
 *   EvLog.update(ev, dtSegundos)   → chamado pelo feed a cada quadro
 *   EvLog.summary()                → {km, kwh, regen, cons, cost, trip, trips}
 *   EvLog.endTrip()                → fecha a viagem atual e guarda
 *   EvLog.setPrice(0.75)           → R$/kWh
 *   EvLog.exportJson()             → string com tudo (para copiar/backup)
 */
(function () {
  'use strict';
  if (window.EvLog) return;

  var KEY = 'evs_log_v1';
  var MAX_TRIPS = 40;

  var state = {
    price: 0.75,          /* R$/kWh, ajustável */
    trips: [],            /* viagens fechadas */
    cur: null             /* viagem em andamento */
  };

  try {
    var raw = localStorage.getItem(KEY);
    if (raw) {
      var saved = JSON.parse(raw);
      if (saved && typeof saved === 'object') {
        state.price = typeof saved.price === 'number' ? saved.price : state.price;
        state.trips = saved.trips instanceof Array ? saved.trips : [];
      }
    }
  } catch (e) {}

  function save() {
    try {
      localStorage.setItem(KEY, JSON.stringify({ price: state.price, trips: state.trips }));
      return true;
    } catch (e) {
      return false;
    }
  }

  function blank(odo) {
    var d = new Date();
    return {
      id: 't' + d.getTime(),
      date: d.toISOString().slice(0, 16).replace('T', ' '),
      km: 0, kwh: 0, regen: 0, vmax: 0, soc0: -1, soc1: -1,
      odo0: odo > -998 ? odo : -1
    };
  }

  /** dt em segundos; só conta com o carro em movimento ou consumindo */
  function update(ev, dt) {
    if (!ev || !(dt > 0)) return state.cur;
    if (dt > 2) dt = 2;                       /* salto de tempo (app pausado) não vira km */
    var moving = ev.speed > 1;
    if (!state.cur) {
      if (!moving && Math.abs(ev.pw || 0) < 0.5) return null;
      state.cur = blank(ev.odo);
      if (ev.soc >= 0) state.cur.soc0 = ev.soc;
    }
    var t = state.cur;
    if (moving) t.km += ev.speed * dt / 3600;
    var pw = ev.pw;
    if (pw > -998) {
      if (pw >= 0) t.kwh += pw * dt / 3600;
      else t.regen += (-pw) * dt / 3600;
    }
    if (ev.speed > t.vmax) t.vmax = ev.speed;
    if (ev.soc >= 0) t.soc1 = ev.soc;

    /* parado por mais de 3 min: fecha a viagem */
    var now = Date.now();
    if (!moving && Math.abs(ev.pw || 0) < 0.5) {
      if (!t.stoppedAt) t.stoppedAt = now;
      else if (now - t.stoppedAt > 180000) endTrip();
    } else {
      t.stoppedAt = 0;
    }
    return t;
  }

  function close(t) {
    t.cons = t.km > 0.3 ? +(t.kwh / t.km * 100).toFixed(1) : 0;      /* kWh/100 km */
    t.cost = +((t.kwh - t.regen) * state.price).toFixed(2);
    t.km = +t.km.toFixed(2);
    t.kwh = +t.kwh.toFixed(3);
    t.regen = +t.regen.toFixed(3);
    return t;
  }

  function endTrip() {
    if (!state.cur) return null;
    var t = close(state.cur);
    state.cur = null;
    if (t.km < 0.3 && t.kwh < 0.01) return null;                      /* ignora o nada */
    state.trips.unshift(t);
    if (state.trips.length > MAX_TRIPS) state.trips.length = MAX_TRIPS;
    save();
    return t;
  }

  function summary() {
    var cur = state.cur ? close(state.cur) : null;
    var sum = { km: 0, kwh: 0, regen: 0, cost: 0 };
    for (var i = 0; i < state.trips.length; i++) {
      var t = state.trips[i];
      sum.km += t.km; sum.kwh += t.kwh; sum.regen += t.regen; sum.cost += t.cost;
    }
    return {
      price: state.price,
      cur: cur,
      trips: state.trips.length,
      total: {
        km: +sum.km.toFixed(1),
        kwh: +sum.kwh.toFixed(2),
        regen: +sum.regen.toFixed(2),
        cost: +sum.cost.toFixed(2),
        cons: sum.km > 0.3 ? +(sum.kwh / sum.km * 100).toFixed(1) : 0
      }
    };
  }

  window.EvLog = {
    update: update,
    endTrip: endTrip,
    summary: summary,
    setPrice: function (v) { state.price = Math.max(0, +v || 0); save(); },
    price: function () { return state.price; },
    clear: function () { state.trips = []; state.cur = null; save(); },
    exportJson: function () { return JSON.stringify({ price: state.price, cur: state.cur, trips: state.trips }); }
  };
})();
