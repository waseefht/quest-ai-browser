(function () {
  // In-page translation to Arabic: text nodes are batched and translated natively (QAB.translate), keeping the page live & logged-in.
  if (window.__qabTrRun) { window.__qabTrRun(document.body); return; }
  var pend = {}, seq = 0, timer = null;
  var SKIP = /^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA|CODE|PRE|INPUT|SELECT|OPTION|SVG|MATH|KBD|SAMP|CANVAS|IFRAME)$/;
  var HAS_FOREIGN = /[A-Za-z\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF\u0590-\u05FF\u0900-\u097F\u0E00-\u0E7F\u3040-\u30FF\u4E00-\u9FFF\uAC00-\uD7AF]/;
  window.__qabTr = function (id, arr) {
    var nodes = pend[id]; delete pend[id]; if (!nodes) return;
    for (var i = 0; i < nodes.length && i < arr.length; i++) {
      var n = nodes[i], v = n.nodeValue, tr = arr[i];
      if (!tr) continue;
      var lead = v.match(/^\s*/)[0], trail = v.match(/\s*$/)[0];
      n.__qabOrig = v; n.nodeValue = lead + tr + trail;
      var p = n.parentElement; if (p && !p.__qabDir) { p.__qabDir = 1; p.setAttribute('dir', 'auto'); }
    }
  };
  function collect(root) {
    var out = [];
    var w = document.createTreeWalker(root || document.body, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        if (n.__qabDone) return NodeFilter.FILTER_REJECT;
        var p = n.parentElement; if (!p || SKIP.test(p.tagName) || p.isContentEditable) return NodeFilter.FILTER_REJECT;
        var s = n.nodeValue.trim();
        if (s.length < 2 || !HAS_FOREIGN.test(s)) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var n; while ((n = w.nextNode())) out.push(n);
    return out;
  }
  function run(root) {
    if (!root) return;
    var nodes = collect(root), batch = [], len = 0;
    function flush() {
      if (!batch.length) return;
      var id = ++seq; pend[id] = batch;
      QAB.translate(id, JSON.stringify(batch.map(function (n) { return n.nodeValue.replace(/\s+/g, ' ').trim(); })));
      batch = []; len = 0;
    }
    nodes.forEach(function (n) { n.__qabDone = 1; batch.push(n); len += n.nodeValue.length; if (batch.length >= 50 || len > 1800) flush(); });
    flush();
    // also translate placeholders / titles
    try { if (document.title && HAS_FOREIGN.test(document.title) && !document.__qabT) { document.__qabT = 1; } } catch (e) {}
  }
  window.__qabTrRun = run;
  run(document.body);
  new MutationObserver(function (m) {
    clearTimeout(timer); timer = setTimeout(function () { run(document.body); }, 900);
  }).observe(document.documentElement, { childList: true, subtree: true, characterData: false });
})();
