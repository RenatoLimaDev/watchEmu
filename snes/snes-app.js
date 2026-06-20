/* ===== WatchEmu — SNES app (EmulatorJS integration) ===== */

// --- PATCH: force preserveDrawingBuffer on WebGL contexts ---
// EmulatorJS uses WebGL; without this, drawImage() reads a cleared buffer
// because the browser discards the framebuffer after compositing.
// Must run BEFORE EmulatorJS creates its canvas.
(function(){
  var orig = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function(type, attrs){
    if (type === "webgl" || type === "webgl2" || type === "experimental-webgl"){
      attrs = Object.assign({}, attrs || {}, { preserveDrawingBuffer: true });
    }
    return orig.call(this, type, attrs);
  };
})();

(function(){
  "use strict";

  // --- config ---
  const EJS_CDN = "https://cdn.emulatorjs.org/stable/data/";
  const SNES_W = 256, SNES_H = 224;
  const MODELS = { fossil:362, tic:332, gw:300 };

  // --- state ---
  let diameter = MODELS.fossil;
  let fitMode = "fill";
  let zoom = 1;
  let ejsCanvas = null;
  let ejsReady = false;
  let soundOn = true;

  // --- DOM ---
  const watchEl   = document.getElementById("watch");
  const screenEl  = document.getElementById("screen");
  const canvas    = document.getElementById("display");
  const dctx      = canvas.getContext("2d");
  const hint      = document.getElementById("hint");
  const fpsEl     = document.getElementById("fps");
  const gameDiv   = document.getElementById("game");
  const loadBar   = document.getElementById("loadingBar");

  // ============================
  //  SIZING
  // ============================
  function applySize(){
    const caseSize = diameter + 34;
    watchEl.style.width  = caseSize + "px";
    watchEl.style.height = caseSize + "px";
    screenEl.style.width  = diameter + "px";
    screenEl.style.height = diameter + "px";
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width  = Math.round(diameter * dpr);
    canvas.height = Math.round(diameter * dpr);
    canvas.style.width  = diameter + "px";
    canvas.style.height = diameter + "px";
    document.getElementById("pushers").style.right = "-13px";
    dctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    dctx.imageSmoothingEnabled = false;
    const knobSize = Math.round(diameter * 0.07);
    const ak = document.getElementById("arcKnob");
    ak.style.width = knobSize + "px";
    ak.style.height = knobSize + "px";
  }

  const fitTap = document.createElement("div");
  fitTap.style.cssText = "position:absolute;left:25%;top:25%;width:50%;height:50%;z-index:2;cursor:pointer;";
  screenEl.appendChild(fitTap);
  fitTap.addEventListener("click", () => {
    fitMode = fitMode === "fill" ? "fit" : "fill";
    const seg = document.getElementById("fitSeg");
    Array.from(seg.children).forEach((b) => {
      b.classList.toggle("active", b.dataset.fit === fitMode);
    });
  });

  // ============================
  //  DRAW (copies EmulatorJS canvas → our watch canvas)
  // ============================
  const BIAS_X = 0.5, BIAS_Y = 0.55;
  const GAME_SHIFT = 0;

  function findEjsCanvas(){
    if (ejsCanvas) return;
    // EmulatorJS creates multiple canvases; we want the one with a WebGL context
    // (the actual game rendering canvas), not UI overlay canvases
    const list = gameDiv.querySelectorAll("canvas");
    for (let i = 0; i < list.length; i++){
      const c = list[i];
      if (c.width < 2 || c.height < 2) continue;
      // Prefer a canvas with a GL context (game canvas)
      const gl = c.getContext("webgl2") || c.getContext("webgl") || c.getContext("experimental-webgl");
      if (gl){ ejsCanvas = c; return; }
    }
    // Fallback: largest canvas
    let best = null, bestArea = 0;
    for (let i = 0; i < list.length; i++){
      const area = list[i].width * list[i].height;
      if (area > bestArea){ bestArea = area; best = list[i]; }
    }
    if (best && bestArea > 4) ejsCanvas = best;
  }

  function drawDebugGrid(){
    dctx.clearRect(0, 0, diameter, diameter);
    let dw, dh, dx, dy;
    if (fitMode === "fill"){
      dh = diameter; dw = diameter * (SNES_W / SNES_H);
      dx = (diameter - dw) / 2; dy = 0;
    } else {
      dw = diameter; dh = diameter * (SNES_H / SNES_W);
      dx = 0; dy = (diameter - dh) / 2;
    }
    dy -= diameter * GAME_SHIFT;
    dctx.fillStyle = "rgba(10,30,60,.45)";
    dctx.fillRect(dx, dy, dw, dh);
    dctx.strokeStyle = "rgba(80,140,255,.25)";
    dctx.lineWidth = 0.5;
    const step = dw / 16;
    for (let x = dx; x <= dx + dw; x += step){
      dctx.beginPath(); dctx.moveTo(x, dy); dctx.lineTo(x, dy + dh); dctx.stroke();
    }
    const stepY = dh / 14;
    for (let y = dy; y <= dy + dh; y += stepY){
      dctx.beginPath(); dctx.moveTo(dx, y); dctx.lineTo(dx + dw, y); dctx.stroke();
    }
    dctx.strokeStyle = "rgba(80,140,255,.5)";
    dctx.lineWidth = 1;
    dctx.strokeRect(dx, dy, dw, dh);
    dctx.fillStyle = "rgba(80,140,255,.5)";
    dctx.font = "11px monospace";
    dctx.textAlign = "center";
    dctx.fillText(SNES_W + "×" + SNES_H + " (" + fitMode + ")", diameter / 2, diameter / 2);
  }

  function draw(){
    dctx.clearRect(0, 0, diameter, diameter);
    if (!ejsReady){ findEjsCanvas(); }
    if (!ejsCanvas){ drawDebugGrid(); return; }
    // Check if canvas still valid
    if (!ejsCanvas.width) { ejsCanvas = null; return; }

    const srcW = ejsCanvas.width  || SNES_W;
    const srcH = ejsCanvas.height || SNES_H;
    const sw = srcW / zoom, sh = srcH / zoom;
    const sx = Math.max(0, Math.min((srcW - sw) * BIAS_X, srcW - sw));
    const sy = Math.max(0, Math.min((srcH - sh) * BIAS_Y, srcH - sh));
    let dw, dh, dx, dy;
    if (fitMode === "fill"){
      dh = diameter; dw = diameter * (SNES_W / SNES_H);
      dx = (diameter - dw) / 2; dy = 0;
    } else {
      dw = diameter; dh = diameter * (SNES_H / SNES_W);
      dx = 0; dy = (diameter - dh) / 2;
    }
    dy -= diameter * GAME_SHIFT;
    dctx.imageSmoothingEnabled = false;
    try { dctx.drawImage(ejsCanvas, sx, sy, sw, sh, dx, dy, dw, dh); } catch(_){}
  }

  // --- main loop (only draw — EmulatorJS runs its own emulation loop) ---
  let fcount = 0, ft = performance.now();
  function loop(now){
    requestAnimationFrame(loop);
    draw();
    fcount++;
    if (now - ft >= 500){
      fpsEl.textContent = Math.round(fcount / ((now - ft) / 1000));
      fcount = 0; ft = now;
    }
  }
  requestAnimationFrame(loop);

  // ============================
  //  INPUT BRIDGE
  //  Our touch controls → synthetic KeyboardEvents → EmulatorJS picks them up
  // ============================

  // EmulatorJS default SNES keyboard mapping
  const SNES_KEYS = {
    A:      { code:"KeyX",       key:"x",       keyCode:88,  ejsId:8 },
    B:      { code:"KeyZ",       key:"z",       keyCode:90,  ejsId:0 },
    X:      { code:"KeyS",       key:"s",       keyCode:83,  ejsId:9 },
    Y:      { code:"KeyA",       key:"a",       keyCode:65,  ejsId:1 },
    L:      { code:"KeyQ",       key:"q",       keyCode:81,  ejsId:10 },
    R:      { code:"KeyW",       key:"w",       keyCode:87,  ejsId:11 },
    START:  { code:"Enter",      key:"Enter",   keyCode:13,  ejsId:3 },
    SELECT: { code:"ShiftRight", key:"Shift",   keyCode:16,  ejsId:2 },
    UP:     { code:"ArrowUp",    key:"ArrowUp",    keyCode:38, ejsId:4 },
    DOWN:   { code:"ArrowDown",  key:"ArrowDown",  keyCode:40, ejsId:5 },
    LEFT:   { code:"ArrowLeft",  key:"ArrowLeft",  keyCode:37, ejsId:6 },
    RIGHT:  { code:"ArrowRight", key:"ArrowRight", keyCode:39, ejsId:7 },
  };

  function sendKey(snesBtn, down){
    const k = SNES_KEYS[snesBtn];
    if (!k) return;
    const type = down ? "keydown" : "keyup";
    const opts = {
      code: k.code, key: k.key, keyCode: k.keyCode, which: k.keyCode,
      bubbles: true, cancelable: true,
    };
    const ev = new KeyboardEvent(type, opts);
    document.dispatchEvent(ev);
    window.dispatchEvent(ev);
    if (ejsCanvas) ejsCanvas.dispatchEvent(new KeyboardEvent(type, opts));
    const gameCanvas = gameDiv.querySelector("canvas");
    if (gameCanvas && gameCanvas !== ejsCanvas)
      gameCanvas.dispatchEvent(new KeyboardEvent(type, opts));
    if (window.EJS_emulator && window.EJS_emulator.gameManager){
      try {
        const gp = window.EJS_emulator.gameManager;
        if (down && gp.simulateInput) gp.simulateInput(0, k.ejsId, 1);
        if (!down && gp.simulateInput) gp.simulateInput(0, k.ejsId, 0);
      } catch(_){}
    }
  }

  // Bind a DOM element to a SNES button (touch + mouse via pointer events)
  function bindBtn(el, snesBtn){
    const down = (e) => {
      e.preventDefault();
      try { el.setPointerCapture(e.pointerId); } catch(_){}
      el.classList.add("on");
      sendKey(snesBtn, true);
    };
    const up = (e) => {
      if (e){ e.preventDefault(); try { el.releasePointerCapture(e.pointerId); } catch(_){} }
      el.classList.remove("on");
      sendKey(snesBtn, false);
    };
    el.addEventListener("pointerdown", down);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
    el.addEventListener("contextmenu", (e) => e.preventDefault());
    return { el };
  }

  // 3 physical pushers + on-screen buttons
  const ctrls = {
    a:      bindBtn(document.getElementById("pA"),        "A"),
    b:      bindBtn(document.getElementById("pB"),        "B"),
    y:      bindBtn(document.getElementById("pY"),        "Y"),
    start:  bindBtn(document.getElementById("btnStart"),  "START"),
    select: bindBtn(document.getElementById("btnSelect"), "SELECT"),
    lb:     bindBtn(document.getElementById("btnLB"),     "L"),
    rb:     bindBtn(document.getElementById("btnRB"),     "R"),
  };

  // Keyboard visual feedback (EmulatorJS handles actual input via native events)
  const KB_VISUAL = {
    "x":"a","z":"b","a":"y","q":"lb","w":"rb","Enter":"start","Shift":"select"
  };
  window.addEventListener("keydown", (e) => {
    const c = KB_VISUAL[e.key]; if (c && ctrls[c]) ctrls[c].el.classList.add("on");
  });
  window.addEventListener("keyup", (e) => {
    const c = KB_VISUAL[e.key]; if (c && ctrls[c]) ctrls[c].el.classList.remove("on");
  });

  // ============================
  //  ARC SLIDER (knob desliza pelo arco inferior da tela)
  // ============================
  (function(){
    const zone  = document.getElementById("arcZone");
    const track = document.getElementById("arcTrack");
    const knob  = document.getElementById("arcKnob");
    let active = false, dir = 0, vdir = 0, running = false, originY = 0, moved = false, startT = 0;
    const DEAD_ANGLE = 0.08;
    const RUN_THRESH = 0.55;
    const VTHRESH = 18;
    const TAP_MS = 260;
    const ARC_FROM = Math.PI * 0.35;
    const ARC_TO   = Math.PI * 0.65;

    function angleFromPointer(ex, ey){
      const r = screenEl.getBoundingClientRect();
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      return Math.atan2(ey - cy, ex - cx);
    }
    const VKNOB_MAX = diameter * 0.06;
    function placeKnob(angle, vOffset){
      const R = diameter / 2;
      const inset = diameter * 0.04;
      const r = R - inset;
      const cx = R, cy = R;
      const x = cx + r * Math.cos(angle);
      const y = cy + r * Math.sin(angle) + (vOffset || 0);
      knob.style.left = x + "px";
      knob.style.top  = y + "px";
      knob.style.transform = "translate(-50%,-50%)";
    }
    function setDir(d){
      if (d === dir) return;
      if (dir ===  1) sendKey("RIGHT", false);
      if (dir === -1) sendKey("LEFT",  false);
      dir = d;
      if (dir ===  1) sendKey("RIGHT", true);
      if (dir === -1) sendKey("LEFT",  true);
    }
    function jumpPulse(ms){
      sendKey("A", true); ctrls.a.el.classList.add("on");
      setTimeout(() => { sendKey("A", false); ctrls.a.el.classList.remove("on"); }, ms);
    }
    let jumping = false;
    function setVDir(d){
      if (d === vdir) return;
      if (vdir ===  1) sendKey("DOWN", false);
      if (vdir === -1 && !jumping){ sendKey("UP", false); }
      vdir = d;
      if (vdir ===  1) sendKey("DOWN", true);
      if (vdir === -1){
        if (dir !== 0){
          if (!jumping){ jumping = true; jumpPulse(200); }
        } else {
          sendKey("UP", true);
        }
      } else { jumping = false; }
    }
    function update(ex, ey){
      let a = angleFromPointer(ex, ey);
      if (a < 0) a += Math.PI * 2;
      a = Math.max(ARC_FROM, Math.min(ARC_TO, a));
      const mid = (ARC_FROM + ARC_TO) / 2;
      const norm = (a - mid) / ((ARC_TO - ARC_FROM) / 2);
      const absNorm = Math.abs(norm);
      if (absNorm > DEAD_ANGLE){ moved = true; setDir(norm > 0 ? -1 : 1); }
      else setDir(0);
      const shouldRun = absNorm > RUN_THRESH && dir !== 0;
      if (shouldRun && !running){ running = true; sendKey("B", true); ctrls.b.el.classList.add("on"); }
      else if (!shouldRun && running){ running = false; sendKey("B", false); ctrls.b.el.classList.remove("on"); }
      const dy = originY - ey;
      const vOff = Math.max(-VKNOB_MAX, Math.min(VKNOB_MAX, -dy * 0.5));
      placeKnob(a, vOff);
      if (dy > VTHRESH){ moved = true; setVDir(-1); }
      else if (dy < -VTHRESH){ moved = true; setVDir(1); }
      else setVDir(0);
    }

    placeKnob((ARC_FROM + ARC_TO) / 2);

    track.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      active = true; moved = false; startT = performance.now(); originY = e.clientY;
      zone.classList.add("active");
      try { track.setPointerCapture(e.pointerId); } catch(_){}
      update(e.clientX, e.clientY);
    });
    track.addEventListener("pointermove", (e) => { if (active) update(e.clientX, e.clientY); });
    function end(e){
      if (!active) return; active = false;
      try { track.releasePointerCapture(e.pointerId); } catch(_){}
      zone.classList.remove("active");
      if (!moved && (performance.now() - startT) < TAP_MS) jumpPulse(130);
      setDir(0);
      setVDir(0);
      if (running){ running = false; sendKey("B", false); ctrls.b.el.classList.remove("on"); }
      jumping = false;
      placeKnob((ARC_FROM + ARC_TO) / 2);
    }
    track.addEventListener("pointerup", end);
    track.addEventListener("pointercancel", end);
    track.addEventListener("contextmenu", (e) => e.preventDefault());
  })();

  // ============================
  //  ROM DIAGNOSTICS (SNES header parser)
  // ============================
  function inspectROM(bytes, name){
    const rows = [];
    rows.push(["Arquivo", name]);
    rows.push(["Tamanho", bytes.length.toLocaleString("pt-BR") + " bytes"]);

    // Detect SMC header (512 bytes extra)
    let offset = 0;
    if (bytes.length % 1024 === 512){
      offset = 512;
      rows.push(["Header SMC", "detectado (512 bytes) — sera removido"]);
    }

    // Try internal header at LoROM (0x7FC0) and HiROM (0xFFC0)
    let headerOff = -1, mapping = "desconhecido";
    const lorom = offset + 0x7FC0;
    const hirom = offset + 0xFFC0;

    function validTitle(off){
      if (off + 21 > bytes.length) return false;
      for (let i = 0; i < 21; i++){
        const c = bytes[off + i];
        if (c < 0x20 || c > 0x7E) return false;
      }
      return true;
    }
    if (validTitle(hirom))     { headerOff = hirom; mapping = "HiROM"; }
    else if (validTitle(lorom)){ headerOff = lorom; mapping = "LoROM"; }

    if (headerOff >= 0){
      let title = "";
      for (let i = 0; i < 21; i++) title += String.fromCharCode(bytes[headerOff + i]);
      rows.push(["Titulo interno", title.trim()]);
      rows.push(["Mapeamento", mapping]);
      const rs = bytes[headerOff + 23];
      if (rs > 0 && rs < 16) rows.push(["ROM", (1 << rs) + " KB"]);
      const ram = bytes[headerOff + 24];
      if (ram > 0 && ram < 10) rows.push(["RAM", (1 << ram) + " KB"]);
      const reg = bytes[headerOff + 25];
      const REGS = {0:"Japao",1:"EUA/Canada",2:"Europa",3:"Suecia",4:"Finlandia",
        5:"Dinamarca",6:"Franca",7:"Holanda",8:"Espanha",9:"Alemanha",
        10:"Italia",11:"China",12:"Indonesia",13:"Coreia"};
      rows.push(["Regiao", REGS[reg] || ("codigo " + reg)]);
    }

    let verdict, cls;
    if (bytes.length < 0x8000){
      cls = "bad";
      verdict = "Arquivo muito pequeno para ser uma ROM SNES valida.";
    } else if (headerOff < 0){
      cls = "warn";
      verdict = "Header interno nao encontrado — pode ser formato nao-padrao. Tentando carregar mesmo assim.";
    } else {
      cls = "good";
      verdict = "ROM SNES detectada: " + mapping + ". Pronta para carregar.";
    }
    return { rows, verdict, cls, offset };
  }

  function renderDiag(d){
    const wrap = document.getElementById("diagRows"); wrap.innerHTML = "";
    d.rows.forEach(function(pair){
      var k = pair[0], v = pair[1];
      var row = document.createElement("div"); row.className = "drow";
      var a = document.createElement("span"); a.textContent = k;
      var b = document.createElement("span");
      if (Array.isArray(v)){ b.textContent = v[0]; b.className = v[1]; } else b.textContent = v;
      row.append(a, b); wrap.append(row);
    });
    var ver = document.getElementById("verdict");
    ver.textContent = d.verdict; ver.className = "verdict " + d.cls;
    document.getElementById("romInfo").hidden = false;
  }

  function showDiagError(msg){
    document.getElementById("diagRows").innerHTML = "";
    var ver = document.getElementById("verdict");
    ver.className = "verdict bad"; ver.textContent = msg;
    document.getElementById("romInfo").hidden = false;
    loadBar.classList.add("gone");
    hint.classList.remove("gone");
    hint.innerHTML = "ROM nao carregou.<br>Veja o diagnostico abaixo";
  }

  // ============================
  //  EmulatorJS LOADER
  // ============================
  function initEmulatorJS(blobUrl){
    // Show loading
    loadBar.classList.remove("gone");
    hint.classList.remove("gone");
    hint.innerHTML = "Carregando core SNES...";

    // Clear previous (if any)
    gameDiv.innerHTML = "";
    ejsCanvas = null;
    ejsReady = false;

    // Remove old loader script
    var old = document.getElementById("ejs-loader");
    if (old) old.remove();

    // Configure EmulatorJS
    window.EJS_player   = "#game";
    window.EJS_core     = "snes9x";
    window.EJS_gameUrl  = blobUrl;
    window.EJS_pathtodata = EJS_CDN;
    window.EJS_startOnLoaded = true;
    window.EJS_color    = "#ffb020";
    window.EJS_backgroundColor = "#0e0f12";

    // Load EmulatorJS loader
    var script = document.createElement("script");
    script.id  = "ejs-loader";
    script.src = EJS_CDN + "loader.js";
    script.onerror = function(){
      showDiagError(
        "Nao foi possivel baixar o EmulatorJS. Verifique sua conexao e " +
        "certifique-se de abrir a pagina via servidor local (python -m http.server)."
      );
    };
    document.body.appendChild(script);

    // Poll until EmulatorJS canvas appears
    var attempts = 0;
    var poll = setInterval(function(){
      attempts++;
      findEjsCanvas();
      if (ejsCanvas){
        clearInterval(poll);
        ejsReady = true;
        loadBar.classList.add("gone");
        hint.classList.add("gone");
      }
      if (attempts > 300){ // ~30s timeout
        clearInterval(poll);
        if (!ejsCanvas){
          showDiagError("Timeout: o core SNES nao respondeu em 30s. Tente recarregar a pagina.");
        }
      }
    }, 100);
  }

  // ============================
  //  ROM FILE LOADING
  // ============================
  var fileInput = document.getElementById("file");
  document.getElementById("loadBtn").addEventListener("click", function(){ fileInput.click(); });

  fileInput.addEventListener("change", function(e){
    var f = e.target.files[0]; if (!f) return;
    var reader = new FileReader();
    reader.onload = function(){
      var bytes = new Uint8Array(reader.result);
      var name = f.name;

      // ZIP handling
      var isZip = (bytes[0] === 0x50 && bytes[1] === 0x4B) || /\.zip$/i.test(name);
      if (isZip){
        if (typeof fflate === "undefined"){ showDiagError("Suporte a .zip indisponivel."); return; }
        try {
          var files = fflate.unzipSync(bytes);
          var names = Object.keys(files).filter(function(n){ return !n.endsWith("/"); });
          var entry = names.find(function(n){ return /\.(smc|sfc)$/i.test(n); });
          if (!entry){
            showDiagError("O .zip abriu, mas nao ha .smc/.sfc dentro" +
              (names.length ? " (achei: " + names.map(function(n){return n.split("/").pop();}).join(", ") + ")." : "."));
            return;
          }
          bytes = files[entry];
          name = f.name + "  ->  " + entry.split("/").pop();
        } catch(err){
          showDiagError("Nao consegui abrir o .zip: " + (err && err.message ? err.message : err));
          return;
        }
      }

      // Diagnostics
      var diag = inspectROM(bytes, name);
      renderDiag(diag);

      if (bytes.length < 0x8000){
        showDiagError(diag.verdict);
        return;
      }

      // Strip SMC header if present
      var rom = diag.offset > 0 ? bytes.subarray(diag.offset) : bytes;

      // Create blob URL and launch EmulatorJS
      var blob = new Blob([rom], { type: "application/octet-stream" });
      var blobUrl = URL.createObjectURL(blob);
      initEmulatorJS(blobUrl);
    };
    reader.readAsArrayBuffer(f);
  });

  // ============================
  //  PANEL CONTROLS
  // ============================
  document.getElementById("model").addEventListener("change", function(e){
    diameter = MODELS[e.target.value] || MODELS.fossil; applySize();
  });
  document.getElementById("fitSeg").addEventListener("click", function(e){
    var b = e.target.closest("button[data-fit]"); if (!b) return;
    fitMode = b.dataset.fit;
    Array.from(e.currentTarget.children).forEach(function(x){ x.classList.toggle("active", x === b); });
  });
  document.getElementById("zoomSeg").addEventListener("click", function(e){
    var b = e.target.closest("button[data-zoom]"); if (!b) return;
    zoom = parseFloat(b.dataset.zoom);
    Array.from(e.currentTarget.children).forEach(function(x){ x.classList.toggle("active", x === b); });
  });

  var soundBtn = document.getElementById("soundBtn");
  soundBtn.addEventListener("click", function(){
    soundOn = !soundOn;
    soundBtn.firstElementChild.textContent = "Som: " + (soundOn ? "ligado" : "mudo");
    // Try to mute/unmute EmulatorJS audio
    if (window.EJS_emulator && window.EJS_emulator.setVolume){
      window.EJS_emulator.setVolume(soundOn ? 1 : 0);
    }
    // Fallback: suspend/resume all audio contexts
    if (typeof AudioContext !== "undefined"){
      var contexts = window.EJS_emulator && window.EJS_emulator.audioContext
        ? [window.EJS_emulator.audioContext]
        : [];
      contexts.forEach(function(ctx){
        if (soundOn) ctx.resume(); else ctx.suspend();
      });
    }
  });

  applySize();
  window.addEventListener("resize", applySize);
})();
