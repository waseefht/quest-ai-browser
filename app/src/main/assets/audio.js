function (cfg) {
  // Web Audio booster / EQ / normalizer for page media. Called as (thisFunction)({gain, preset, norm}).
  var A = window.__qabAudio;
  if (!A) {
    A = window.__qabAudio = { ctx: null, hooked: 0, skipped: 0 };
    A.init = function () {
      if (A.ctx) return;
      var C = window.AudioContext || window.webkitAudioContext; if (!C) return;
      var x = A.ctx = new C();
      A.low = x.createBiquadFilter(); A.low.type = 'lowshelf'; A.low.frequency.value = 150;
      A.mid = x.createBiquadFilter(); A.mid.type = 'peaking'; A.mid.frequency.value = 2500; A.mid.Q.value = 0.9;
      A.high = x.createBiquadFilter(); A.high.type = 'highshelf'; A.high.frequency.value = 6000;
      A.comp = x.createDynamicsCompressor(); A.gain = x.createGain();
      A.low.connect(A.mid); A.mid.connect(A.high); A.high.connect(A.comp); A.comp.connect(A.gain); A.gain.connect(x.destination);
      var resume = function () { if (x.state !== 'running') x.resume(); };
      document.addEventListener('pointerdown', resume, true);
      document.addEventListener('play', function (e) { A.hook(e.target); resume(); }, true);
    };
    A.safe = function (m) {
      var s = m.currentSrc || m.src || '';
      if (!s) { var so = m.querySelector('source'); s = so ? so.src : ''; }
      if (!s || s.indexOf('blob:') === 0 || s.indexOf('data:') === 0) return true;  // MSE players (YouTube etc.)
      if (m.crossOrigin) return true;
      try { return new URL(s, location.href).origin === location.origin; } catch (e) { return false; }
    };
    A.hook = function (m) {
      if (!(m instanceof HTMLMediaElement) || m.__qabSrc || !A.ctx) return;
      if (!A.safe(m)) { if (!m.__qabSkip) { m.__qabSkip = 1; A.skipped++; } return; }
      try { m.__qabSrc = A.ctx.createMediaElementSource(m); m.__qabSrc.connect(A.low); A.hooked++; } catch (e) { A.skipped++; }
    };
  }
  A.init(); if (!A.ctx) return;
  var lo = 0, mi = 0, hi = 0, p = cfg.preset;
  if (p === 'voice') { lo = -5; mi = 7; hi = 3; } else if (p === 'bass') { lo = 10; mi = 0; hi = 1; }
  else if (p === 'movie') { lo = 6; mi = 3; hi = 2; } else if (p === 'treble') { lo = -2; mi = 1; hi = 8; }
  A.low.gain.value = lo; A.mid.gain.value = mi; A.high.gain.value = hi;
  var c = A.comp;
  if (cfg.norm) { c.threshold.value = -32; c.knee.value = 24; c.ratio.value = 6; c.attack.value = 0.003; c.release.value = 0.25; }
  else if (cfg.gain > 100) { c.threshold.value = -6; c.knee.value = 6; c.ratio.value = 12; c.attack.value = 0.002; c.release.value = 0.1; } // limiter
  else { c.threshold.value = 0; c.knee.value = 0; c.ratio.value = 1; }
  A.gain.gain.value = (cfg.norm ? 1.6 : 1) * cfg.gain / 100;
  document.querySelectorAll('video,audio').forEach(A.hook);
  if (A.ctx.state !== 'running') A.ctx.resume();
  try { QAB.audioStatus(A.hooked, A.skipped); } catch (e) {}
}
