/*
 * EngineModel — trem de força simplificado: pedal → velocidade, marcha e giro.
 *
 * Não é simulador físico: é o mínimo para o som reagir como num carro de verdade —
 * troca de marcha, kickdown, corte de giro, ponto morto e estouro ao tirar o pé.
 * Código próprio; os números do carro vêm do perfil (engine/profiles/*.json).
 *
 *   var m = EngineModel.create(profile);
 *   m.step(dt, {tps: 0..1, brake: 0..1});       // dt em segundos
 *   m.state  // {speed, rpm, gear, tps, load, limiter, pops}
 */
(function () {
  'use strict';

  var GEARS_DEFAULT = [3.9, 2.4, 1.6, 1.2, 0.95, 0.78];
  var FINAL_DEFAULT = 3.55;
  var TYRE_M = 2.35;         /* circunferência do pneu (m) */

  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }

  function create(profile) {
    profile = profile || {};
    var gears = profile.gears || GEARS_DEFAULT;
    var final = profile.finalDrive || FINAL_DEFAULT;
    var redline = profile.redline || 6800;
    var idle = profile.idle || 700;
    var limiter = profile.limiter || redline;
    var maxAccel = profile.maxAccel || 4.2;      /* m/s² a fundo em 1ª */
    var curves = profile.torque || [[800, 0.55], [2000, 0.9], [4000, 1], [5500, 0.92], [redline, 0.75]];

    var st = {
      speed: 0,        /* km/h */
      rpm: idle,
      gear: 1,
      tps: 0,
      load: 0,
      prevTps: 0,
      shiftT: 0,
      pops: 0
    };

    function torqueAt(rpm) {
      for (var i = 1; i < curves.length; i++) {
        if (rpm <= curves[i][0]) {
          var a = curves[i - 1], b = curves[i];
          var t = (rpm - a[0]) / Math.max(1, b[0] - a[0]);
          return a[1] + (b[1] - a[1]) * clamp(t, 0, 1);
        }
      }
      return curves[curves.length - 1][1];
    }

    /* giro que a roda impõe na marcha atual */
    function rpmFor(speed, gear) {
      var mps = speed / 3.6;
      return mps / TYRE_M * gears[gear - 1] * final * 60;
    }

    function speedFor(rpm, gear) {
      return rpm / 60 / (gears[gear - 1] * final) * TYRE_M * 3.6;
    }

    function step(dt, input) {
      if (!(dt > 0)) dt = 0.05;
      if (dt > 0.25) dt = 0.25;
      var tps = clamp(input && input.tps != null ? input.tps : 0, 0, 1);
      var brake = clamp(input && input.brake != null ? input.brake : 0, 0, 1);

      /* embreagem aberta (parado ou quase): o motor só sobe de giro */
      var clutchOpen = st.speed < 2.5 && st.gear <= 1;
      var target;
      if (clutchOpen) {
        target = idle + tps * (limiter - idle) * 0.92;
      } else {
        target = rpmFor(st.speed, st.gear);
        /* troca de marcha automática */
        if (st.shiftT > 0) st.shiftT -= dt;
        if (st.shiftT <= 0) {
          if (target > redline - 400 && st.gear < gears.length && tps > 0.15) {
            st.gear++; st.shiftT = 0.28; target = rpmFor(st.speed, st.gear);
          } else if (target < idle + 450 && st.gear > 1) {
            st.gear--; st.shiftT = 0.22; target = rpmFor(st.speed, st.gear);
          }
          /* kickdown: pé a fundo em giro baixo */
          if (tps > 0.9 && st.gear > 1 && target < 2300 && st.shiftT <= 0) {
            st.gear--; st.shiftT = 0.3; target = rpmFor(st.speed, st.gear);
          }
        }
      }
      target = clamp(target, idle * 0.9, limiter);
      /* giro persegue o alvo (inércia) */
      var k = clutchOpen ? 9 : 16;
      st.rpm += (target - st.rpm) * clamp(dt * k, 0, 1);

      /* no corte, o giro fica batendo no limitador */
      if (st.rpm >= limiter - 30) st.rpm = limiter - 30 * Math.random();

      /* aceleração: torque na roda menos arrasto e freio */
      var force = tps * maxAccel * torqueAt(st.rpm) * (st.gear === 1 ? 1 : 1 / Math.sqrt(st.gear));
      var drag = 0.0009 * st.speed * st.speed + (brake > 0 ? 3.5 * brake : 0.35);
      var accel = force - drag;
      st.speed = Math.max(0, st.speed + accel * dt * 3.6);

      /* estouro ao tirar o pé com giro alto */
      if (st.prevTps > 0.45 && tps < 0.12 && st.rpm > redline * 0.45 && (profile.pops || 0) > 0) {
        st.pops = 1;
      }
      st.prevTps = tps;
      st.tps = tps;
      st.load = clamp(tps * 0.85 + (st.rpm / redline) * 0.15, 0, 1);
      return st;
    }

    return { step: step, state: st, rpmFor: rpmFor, speedFor: speedFor, torqueAt: torqueAt };
  }

  window.EngineModel = { create: create };
})();
