/*
 * EngineSynth — sintetizador de som de motor em tempo real, dirigido por perfil JSON.
 *
 * Código próprio, sem amostras obrigatórias: o timbre sai de harmônicos, corte de escape,
 * ruído de admissão, turbo e estouros. Cada motor é um JSON (engine/profiles/*.json), então
 * mudar o som é editar texto — nada de recompilar.
 *
 * API:
 *   var voice = EngineSynth.create(ctx, profile);   // ctx: AudioContext ou OfflineAudioContext
 *   voice.update({rpm, tps, load, speed, on});      // ~20 Hz
 *   voice.pop(forte);                               // estouro no escape (0..1)
 *   voice.dispose();
 *
 * Nada aqui depende de DOM: funciona igual dentro da skin, numa página de teste ou no
 * OfflineAudioContext (que é como o som é verificado e exportado em WAV).
 */
(function () {
  'use strict';

  var HARMONICS = 24;

  /* ---------- utilidades ---------- */
  function lerp(a, b, t) { return a + (b - a) * (t < 0 ? 0 : t > 1 ? 1 : t); }

  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }

  /* ruído determinístico (mesmo WAV a cada render) */
  function noiseBuffer(ctx, seconds, seed) {
    var n = Math.floor(ctx.sampleRate * seconds);
    var buf = ctx.createBuffer(1, n, ctx.sampleRate);
    var d = buf.getChannelData(0);
    var s = seed || 1;
    for (var i = 0; i < n; i++) {
      s = (s * 1664525 + 1013904223) % 4294967296;
      d[i] = (s / 2147483648) - 1;
    }
    return buf;
  }

  /* onda de pulsos: harmônicos do perfil, ou 1/k^sharp quando o perfil não lista */
  function pulseWave(ctx, profile) {
    var real = new Float32Array(HARMONICS + 1);
    var imag = new Float32Array(HARMONICS + 1);
    var listed = profile.harmonics || {};
    var sharp = profile.pulseSharp == null ? 1.15 : profile.pulseSharp;
    for (var k = 1; k <= HARMONICS; k++) {
      var amp = listed[k] != null ? listed[k] : Math.pow(k, -sharp);
      if (profile.harmonicsMax && k > profile.harmonicsMax) amp = 0;
      real[k] = amp * (k % 2 ? 1 : -0.9);   // fases alternadas: pulso mais "batido"
      imag[k] = amp * 0.35;
    }
    return ctx.createPeriodicWave(real, imag, { disableNormalization: false });
  }

  /* ---------- voz ---------- */
  function create(ctx, profile) {
    profile = profile || {};
    var p = {
      cylinders: profile.cylinders || 8,
      idle: profile.idle || 700,
      redline: profile.redline || 6800,
      cut: profile.exhaustCut || [110, 900],
      intake: profile.intake || [0.015, 0.16],
      turbo: profile.turbo || 0,
      pops: profile.pops == null ? 0.4 : profile.pops,
      q: profile.filterQ || 1.1,
      drive: profile.drive == null ? 0.7 : profile.drive,
      mix: profile.oscMix || 0.55,
      wave: pulseWave(ctx, profile)
    };

    var out = ctx.createGain();
    out.gain.value = 0;

    /* saturação suave: dá o "rosnado" sem estourar */
    var shaper = ctx.createWaveShaper();
    var curve = new Float32Array(1024);
    for (var i = 0; i < 1024; i++) {
      var x = (i / 1023) * 2 - 1;
      curve[i] = Math.tanh(x * (1 + p.drive * 2.2));
    }
    shaper.curve = curve;
    shaper.oversample = '2x';

    var comp = ctx.createDynamicsCompressor();
    comp.threshold.value = -18;
    comp.ratio.value = 4;
    comp.attack.value = 0.004;
    comp.release.value = 0.12;

    var lp = ctx.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.value = p.cut[0];
    lp.Q.value = p.q;

    /* dois osciladores: o segundo levemente desafinado dá a irregularidade do motor */
    var oscA = ctx.createOscillator();
    var oscB = ctx.createOscillator();
    oscA.setPeriodicWave(p.wave);
    oscB.setPeriodicWave(p.wave);
    var gA = ctx.createGain();
    var gB = ctx.createGain();
    gA.gain.value = p.mix;
    gB.gain.value = 1 - p.mix;
    oscA.connect(gA).connect(lp);
    oscB.connect(gB).connect(lp);

    /* admissão: ruído filtrado, abre com o pedal */
    var noise = ctx.createBufferSource();
    noise.buffer = noiseBuffer(ctx, 2, 7);
    noise.loop = true;
    var intakeBP = ctx.createBiquadFilter();
    intakeBP.type = 'bandpass';
    intakeBP.frequency.value = 900;
    intakeBP.Q.value = 0.8;
    var intakeGain = ctx.createGain();
    intakeGain.gain.value = 0;
    noise.connect(intakeBP).connect(intakeGain).connect(shaper);

    /* turbo: assobio fino que sobe com a carga */
    var turboOsc = ctx.createOscillator();
    turboOsc.type = 'sine';
    turboOsc.frequency.value = 3000;
    var turboGain = ctx.createGain();
    turboGain.gain.value = 0;
    turboOsc.connect(turboGain).connect(shaper);

    lp.connect(shaper).connect(comp).connect(out);
    oscA.start(0);
    oscB.start(0);
    noise.start(0);
    turboOsc.start(0);

    var disposed = false;
    var lastPop = 0;

    function update(s) {
      if (disposed) return;
      var now = ctx.currentTime;
      var rpm = clamp(s.rpm || p.idle, 200, p.redline * 1.05);
      var load = clamp(s.load == null ? 0.4 : s.load, 0, 1);
      var on = s.on !== false;
      /* frequência de explosão: 4 tempos → rpm/60 × cilindros/2 */
      var fire = rpm / 60 * (p.cylinders / 2);
      var t = clamp((rpm - p.idle) / (p.redline - p.idle), 0, 1);

      oscA.frequency.setTargetAtTime(fire, now, 0.02);
      oscB.frequency.setTargetAtTime(fire * (1 + 0.006 + 0.004 * Math.sin(now * 3.7)), now, 0.03);

      var cut = lerp(p.cut[0], p.cut[1], Math.pow(t, 0.75)) * (0.55 + 0.45 * load);
      lp.frequency.setTargetAtTime(clamp(cut, 60, 12000), now, 0.03);

      intakeBP.frequency.setTargetAtTime(clamp(fire * 7, 300, 6000), now, 0.03);
      var ig = lerp(p.intake[0], p.intake[1], Math.pow(t, 0.8)) * (0.25 + 0.75 * load);
      intakeGain.gain.setTargetAtTime(on ? ig : 0, now, 0.05);

      turboOsc.frequency.setTargetAtTime(1800 + 7200 * load, now, 0.05);
      turboGain.gain.setTargetAtTime(on ? p.turbo * load * 0.05 : 0, now, 0.05);

      var vol = on ? lerp(0.22, 1, Math.pow(t, 0.6)) * (0.45 + 0.55 * load) : 0;
      out.gain.setTargetAtTime(vol, now, 0.04);
    }

    /* estouro no escape: ruído com decaimento curto, filtrado grave */
    function pop(strength) {
      if (disposed || p.pops <= 0) return;
      var now = ctx.currentTime;
      if (now - lastPop < 0.05) return;
      lastPop = now;
      var src = ctx.createBufferSource();
      src.buffer = noise.buffer;
      src.playbackRate.value = 0.6 + Math.random() * 0.7;
      var bp = ctx.createBiquadFilter();
      bp.type = 'bandpass';
      bp.frequency.value = 160 + Math.random() * 260;
      bp.Q.value = 1.4;
      var env = ctx.createGain();
      var peak = 0.35 * (strength == null ? 1 : strength);
      env.gain.setValueAtTime(0.0001, now);
      env.gain.exponentialRampToValueAtTime(peak, now + 0.006);
      env.gain.exponentialRampToValueAtTime(0.0001, now + 0.09);
      src.connect(bp).connect(env).connect(out);
      src.start(now, Math.random() * 1.5, 0.14);
      src.onended = function () { try { src.disconnect(); env.disconnect(); bp.disconnect(); } catch (e) {} };
    }

    function dispose() {
      disposed = true;
      try { oscA.stop(); oscB.stop(); noise.stop(); turboOsc.stop(); } catch (e) {}
      try { out.disconnect(); } catch (e) {}
    }

    function destination() { return out; }

    return { update: update, pop: pop, dispose: dispose, output: out, destination: destination, profile: p };
  }

  window.EngineSynth = { create: create, noiseBuffer: noiseBuffer, pulseWave: pulseWave };
})();
