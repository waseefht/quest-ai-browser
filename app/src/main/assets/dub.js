(function () {
  // Live caption capture for Arabic dubbing: <track> cues + YouTube/common player caption DOM.
  if (window.__qabDub) { window.__qabDub.on(); return; }
  var last = '', timer = null, on = true;
  function send(t) {
    t = (t || '').replace(/\s+/g, ' ').trim();
    if (!on || !t || t === last) return;
    last = t; try { QAB.caption(t); } catch (e) {}
  }
  function hookTracks() {
    document.querySelectorAll('video,audio').forEach(function (v) {
      var tt = v.textTracks; if (!tt) return;
      var chosen = null;
      for (var i = 0; i < tt.length; i++) {
        var tr = tt[i];
        if (tr.kind !== 'subtitles' && tr.kind !== 'captions') continue;
        if (tr.mode === 'showing') { chosen = tr; break; }
        if (!chosen) chosen = tr;
      }
      if (!chosen || chosen.__qab) return;
      chosen.__qab = 1; if (chosen.mode === 'disabled') chosen.mode = 'hidden';
      chosen.addEventListener('cuechange', function () {
        var c = this.activeCues; if (!c || !c.length) return;
        var s = []; for (var j = 0; j < c.length; j++) s.push((c[j].text || '').replace(/<[^>]+>/g, ''));
        send(s.join(' '));
      });
    });
  }
  function ytCaptionsOn() {
    var b = document.querySelector('.ytp-subtitles-button[aria-pressed="false"]');
    if (b && !b.__qab) { b.__qab = 1; b.click(); }
  }
  function scanDom() {
    var t = '';
    var seg = document.querySelectorAll('.ytp-caption-segment, .caption-visual-line');
    if (seg.length) seg.forEach(function (n) { t += n.textContent + ' '; });
    else {
      var c = document.querySelector('.vjs-text-track-display, .jw-text-track-container, .shaka-text-container, .player-timedtext, .plyr__captions, [class*="captions-text"], [class*="subtitle-text"]');
      if (c) t = c.innerText || c.textContent;
    }
    if (t && t.trim()) { clearTimeout(timer); timer = setTimeout(function () { send(t); }, 450); }
  }
  new MutationObserver(function () { if (on) { scanDom(); hookTracks(); ytCaptionsOn(); } })
    .observe(document.documentElement, { subtree: true, childList: true, characterData: true });
  hookTracks(); ytCaptionsOn();
  window.__qabDuck = function (d, level) {
    document.querySelectorAll('video,audio').forEach(function (v) {
      if (d) { if (v.__qabVol === undefined) v.__qabVol = v.volume; v.volume = Math.min(v.__qabVol, level); }
      else if (v.__qabVol !== undefined) { v.volume = v.__qabVol; delete v.__qabVol; }
    });
  };
  window.__qabDub = {
    on: function () { on = true; last = ''; hookTracks(); ytCaptionsOn(); },
    off: function () { on = false; window.__qabDuck(false); }
  };
})();
