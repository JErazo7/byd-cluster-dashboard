/*
 * EngineRender — renderiza o som fora do tempo real (OfflineAudioContext).
 *
 * Serve para três coisas:
 *   1. exportar WAV para ouvir no celular/PC antes de instalar no carro;
 *   2. conferir, por número, se a frequência fundamental saiu como esperado (verificação);
 *   3. gerar trilhas de demonstração para o README.
 *
 *   EngineRender.render(profile, segundos, roteiro, sampleRate) -> Promise<AudioBuffer>
 *   EngineRender.wav(buffer) -> Uint8Array (PCM 16 bits mono)
 *   EngineRender.analyze(buffer) -> {rms, peak, freq, expected, ratio}
 *
 * "roteiro" é uma função (t, modelo) => {tps, brake} chamada a 20 Hz.
 */
(function () {
  'use strict';

  var STEP = 0.05;

  function render(profile, seconds, script, sampleRate) {
    var sr = sampleRate || 44100;
    var OAC = window.OfflineAudioContext || window.webkitOfflineAudioContext;
    var ctx = new OAC(1, Math.floor(sr * seconds), sr);
    var voice = window.EngineSynth.create(ctx, profile);
    var model = window.EngineModel.create(profile);
    voice.output.connect(ctx.destination);

    var n = Math.floor(seconds / STEP);
    for (var i = 0; i < n; i++) {
      var input = script ? script(i * STEP, model) : { tps: 0.8, brake: 0 };
      var st = model.step(STEP, input);
      voice.update({ rpm: st.rpm, tps: st.tps, load: st.load, speed: st.speed, on: true });
      if (st.pops) { voice.pop(0.9); st.pops = 0; }
    }
    return ctx.startRendering();
  }

  /* giro fixo, sem modelo: é assim que a fundamental é conferida (rpm → Hz) */
  function renderFixed(profile, rpm, seconds, sampleRate) {
    var sr = sampleRate || 44100;
    var OAC = window.OfflineAudioContext || window.webkitOfflineAudioContext;
    var ctx = new OAC(1, Math.floor(sr * (seconds || 2)), sr);
    var voice = window.EngineSynth.create(ctx, profile);
    voice.output.connect(ctx.destination);
    voice.update({ rpm: rpm, tps: 0.6, load: 0.6, speed: 60, on: true });
    return ctx.startRendering();
  }

  /* PCM 16 bits mono, cabeçalho WAV de 44 bytes */
  function wav(buffer) {
    var d = buffer.getChannelData(0);
    var len = d.length;
    var out = new Uint8Array(44 + len * 2);
    var view = new DataView(out.buffer);
    function str(off, s) { for (var i = 0; i < s.length; i++) out[off + i] = s.charCodeAt(i); }
    str(0, 'RIFF'); view.setUint32(4, 36 + len * 2, true); str(8, 'WAVE');
    str(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
    view.setUint16(22, 1, true); view.setUint32(24, buffer.sampleRate, true);
    view.setUint32(28, buffer.sampleRate * 2, true); view.setUint16(32, 2, true);
    view.setUint16(34, 16, true); str(36, 'data'); view.setUint32(40, len * 2, true);
    for (var i = 0; i < len; i++) {
      var v = Math.max(-1, Math.min(1, d[i]));
      view.setInt16(44 + i * 2, v < 0 ? v * 0x8000 : v * 0x7fff, true);
    }
    return out;
  }

  /* energia numa frequência (Goertzel), janela de 0,4 s no meio do buffer */
  function goertzel(d, sr, freq, start) {
    var n = Math.floor(sr * 0.4);
    if (start + n > d.length) n = d.length - start;
    var k = 2 * Math.PI * freq / sr;
    var c = 2 * Math.cos(k), s0 = 0, s1 = 0, s2 = 0;
    for (var i = 0; i < n; i++) {
      s0 = d[start + i] + c * s1 - s2;
      s2 = s1; s1 = s0;
    }
    return s1 * s1 + s2 * s2 - c * s1 * s2;
  }

  /* fundamental medida: varre 20..400 Hz e acha o pico; compara com o esperado do perfil */
  function analyze(buffer, profile) {
    var d = buffer.getChannelData(0);
    var sr = buffer.sampleRate;
    var start = Math.floor(d.length * 0.55);
    var rms = 0, peak = 0;
    for (var i = start; i < d.length; i++) {
      rms += d[i] * d[i];
      peak = Math.max(peak, Math.abs(d[i]));
    }
    rms = Math.sqrt(rms / Math.max(1, d.length - start));
    var best = 0, bestE = -1;
    for (var f = 20; f <= 400; f += 0.5) {
      var e = goertzel(d, sr, f, start);
      if (e > bestE) { bestE = e; best = f; }
    }
    var expected = (profile.limiter * 0.55) / 60 * (profile.cylinders / 2);
    return {
      rms: +rms.toFixed(4),
      peak: +peak.toFixed(4),
      freq: best,
      expected: +expected.toFixed(1),
      ratio: +(best / expected).toFixed(2)
    };
  }

  window.EngineRender = { render: render, renderFixed: renderFixed, wav: wav, analyze: analyze, goertzel: goertzel };
})();
