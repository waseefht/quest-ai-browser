(function () {
  // In-page 360°/180° viewer: renders the page's own <video> onto an inside-out WebGL sphere.
  var mode = window.__qabVrMode || '360';
  if (window.__qabVR) { window.__qabVR.setMode(mode); return; }
  var vids = [].slice.call(document.querySelectorAll('video'));
  if (!vids.length) { QAB.vrFail('', 'novideo'); return; }
  vids.sort(function (a, b) { return (b.paused ? 0 : 1e9) + b.videoWidth * b.videoHeight - ((a.paused ? 0 : 1e9) + a.videoWidth * a.videoHeight); });
  var v = vids[0];
  var wrap = document.createElement('div');
  wrap.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:2147483647;background:#000;touch-action:none;user-select:none';
  var cv = document.createElement('canvas'); cv.style.cssText = 'width:100%;height:100%;display:block'; wrap.appendChild(cv);
  var bar = document.createElement('div');
  bar.style.cssText = 'position:absolute;left:0;right:0;bottom:18px;display:flex;flex-wrap:wrap;gap:10px;justify-content:center;direction:rtl;font:20px sans-serif';
  wrap.appendChild(bar);
  var yaw = 0, pitch = 0, fov = 90;
  function btn(t, f) {
    var b = document.createElement('button'); b.textContent = t;
    b.style.cssText = 'min-width:64px;min-height:56px;padding:0 18px;border-radius:14px;border:0;background:rgba(35,35,35,.85);color:#fff;font-size:20px';
    b.onclick = function (e) { e.stopPropagation(); f(); }; bar.appendChild(b); return b;
  }
  btn('✕ خروج', close);
  btn('⏯', function () { v.paused ? v.play() : v.pause(); });
  btn('⏪ 10', function () { v.currentTime = Math.max(0, v.currentTime - 10); });
  btn('10 ⏩', function () { v.currentTime += 10; });
  btn('🎯', function () { yaw = 0; pitch = 0; });
  var mb = btn(mode, function () { var m = ['360', '180', '180sbs', '360tb', '360sbs']; setMode(m[(m.indexOf(mode) + 1) % m.length]); });
  btn('➕', function () { fov = Math.max(30, fov - 10); });
  btn('➖', function () { fov = Math.min(120, fov + 10); });
  function setMode(m) { mode = m; mb.textContent = m; }
  document.body.appendChild(wrap);
  try { (wrap.requestFullscreen || wrap.webkitRequestFullscreen).call(wrap); } catch (e) {}

  var gl = cv.getContext('webgl') || cv.getContext('experimental-webgl');
  if (!gl) { close(); QAB.vrFail(v.currentSrc || '', 'nogl'); return; }
  function sh(t, s) { var o = gl.createShader(t); gl.shaderSource(o, s); gl.compileShader(o); return o; }
  var pr = gl.createProgram();
  gl.attachShader(pr, sh(gl.VERTEX_SHADER, 'attribute vec3 p;attribute vec2 uv;uniform mat4 M;varying vec2 t;void main(){t=uv;gl_Position=M*vec4(p,1.0);}'));
  gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, 'precision mediump float;varying vec2 t;uniform sampler2D s;uniform vec4 R;uniform float H;void main(){vec2 c=t;if(H>0.5){c.x=(c.x-0.25)*2.0;if(c.x<0.0||c.x>1.0){gl_FragColor=vec4(0.0,0.0,0.0,1.0);return;}}gl_FragColor=texture2D(s,vec2(R.x+c.x*R.z,R.y+c.y*R.w));}'));
  gl.linkProgram(pr); gl.useProgram(pr);
  var LAT = 40, LON = 80, pos = [], idx = [];
  for (var i = 0; i <= LAT; i++) for (var j = 0; j <= LON; j++) {
    var th = Math.PI * i / LAT, ph = 2 * Math.PI * j / LON;
    pos.push(-Math.sin(th) * Math.sin(ph) * 10, Math.cos(th) * 10, Math.sin(th) * Math.cos(ph) * 10, j / LON, i / LAT);
  }
  for (i = 0; i < LAT; i++) for (j = 0; j < LON; j++) { var a = i * (LON + 1) + j, b = a + LON + 1; idx.push(a, b, a + 1, b, b + 1, a + 1); }
  var vb = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vb); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(pos), gl.STATIC_DRAW);
  var ib = gl.createBuffer(); gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ib); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array(idx), gl.STATIC_DRAW);
  var aP = gl.getAttribLocation(pr, 'p'), aT = gl.getAttribLocation(pr, 'uv');
  gl.enableVertexAttribArray(aP); gl.vertexAttribPointer(aP, 3, gl.FLOAT, false, 20, 0);
  gl.enableVertexAttribArray(aT); gl.vertexAttribPointer(aT, 2, gl.FLOAT, false, 20, 12);
  var tex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  var uM = gl.getUniformLocation(pr, 'M'), uR = gl.getUniformLocation(pr, 'R'), uH = gl.getUniformLocation(pr, 'H');

  function persp(f, asp) { var t = 1 / Math.tan(f * Math.PI / 360), n = 0.1, fa = 100; return [t / asp, 0, 0, 0, 0, t, 0, 0, 0, 0, (fa + n) / (n - fa), -1, 0, 0, 2 * fa * n / (n - fa), 0]; }
  function mul(a, b) { var o = new Array(16); for (var c = 0; c < 4; c++) for (var r = 0; r < 4; r++) { var s = 0; for (var k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k]; o[c * 4 + r] = s; } return o; }
  function rx(d) { var r = d * Math.PI / 180, c = Math.cos(r), s = Math.sin(r); return [1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1]; }
  function ry(d) { var r = d * Math.PI / 180, c = Math.cos(r), s = Math.sin(r); return [c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1]; }

  var running = true, failed = false;
  function frame() {
    if (!running) return;
    var w = cv.clientWidth * (window.devicePixelRatio || 1), h = cv.clientHeight * (window.devicePixelRatio || 1);
    if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
    gl.viewport(0, 0, cv.width, cv.height); gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT);
    if (v.readyState >= 2) {
      try { gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, v); }
      catch (e) { if (!failed) { failed = true; close(); QAB.vrFail(v.currentSrc && v.currentSrc.indexOf('blob:') !== 0 ? v.currentSrc : '', 'cors'); } return; }
    }
    gl.uniformMatrix4fv(uM, false, new Float32Array(mul(persp(fov, cv.width / Math.max(1, cv.height)), mul(rx(pitch), ry(yaw)))));
    var R = /sbs$/.test(mode) ? [0, 0, 0.5, 1] : /tb$/.test(mode) ? [0, 0, 1, 0.5] : [0, 0, 1, 1];
    gl.uniform4f(uR, R[0], R[1], R[2], R[3]); gl.uniform1f(uH, mode.indexOf('180') === 0 ? 1 : 0);
    gl.drawElements(gl.TRIANGLES, idx.length, gl.UNSIGNED_SHORT, 0);
    requestAnimationFrame(frame);
  }
  var lx = 0, ly = 0, down = false;
  cv.addEventListener('pointerdown', function (e) { down = true; lx = e.clientX; ly = e.clientY; cv.setPointerCapture && cv.setPointerCapture(e.pointerId); });
  cv.addEventListener('pointermove', function (e) {
    if (!down) return; var k = fov / Math.max(1, cv.clientHeight);
    yaw -= (e.clientX - lx) * k; pitch = Math.max(-89, Math.min(89, pitch + (e.clientY - ly) * k)); lx = e.clientX; ly = e.clientY;
  });
  window.addEventListener('pointerup', function () { down = false; });
  // Quest controller thumbstick arrives as wheel events → look around
  wrap.addEventListener('wheel', function (e) { e.preventDefault(); yaw += e.deltaX * 0.15; pitch = Math.max(-89, Math.min(89, pitch - e.deltaY * 0.15)); }, { passive: false });
  function close() {
    running = false; try { if (document.fullscreenElement) document.exitFullscreen(); } catch (e) {}
    if (wrap.parentNode) wrap.parentNode.removeChild(wrap); window.__qabVR = null;
  }
  window.__qabVR = { close: close, setMode: setMode };
  if (v.paused) { try { v.play(); } catch (e) {} }
  requestAnimationFrame(frame);
})();
