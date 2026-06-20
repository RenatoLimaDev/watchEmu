/* ===== Bancada do relogio — NES app ===== */
(function(){
  "use strict";
  const NW = 256, NH = 240;

  // --- model presets: display diameter (px) per representative watch ---
  const MODELS = { fossil:362, tic:332, gw:300 };
  let diameter = MODELS.fossil;
  let fitMode = "fill"; // "fill" | "fit"
  let zoom = 1;         // 1 | 1.5 | 2

  const watchEl = document.getElementById("watch");
  const screenEl = document.getElementById("screen");
  const canvas = document.getElementById("display");
  const dctx = canvas.getContext("2d");
  const hint = document.getElementById("hint");
  const fpsEl = document.getElementById("fps");

  // offscreen NES framebuffer
  const buf = document.createElement("canvas");
  buf.width = NW; buf.height = NH;
  const bctx = buf.getContext("2d");
  const image = bctx.createImageData(NW, NH);
  const fb_u8 = new Uint8ClampedArray(image.data.buffer);
  const fb_u32 = new Uint32Array(image.data.buffer);
  for (let i = 0; i < fb_u32.length; i++) fb_u32[i] = 0xff000000;

  // --- audio (ring buffer fed by jsnes, drained by ScriptProcessor) ---
  const SAMPLES = 8192, MASK = SAMPLES - 1;
  const aL = new Float32Array(SAMPLES), aR = new Float32Array(SAMPLES);
  let wr = 0, rd = 0, audioCtx = null, gain = null, soundOn = true;

  function initAudio(){
    if (audioCtx) return;
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    audioCtx = new AC();
    gain = audioCtx.createGain();
    gain.gain.value = soundOn ? 0.6 : 0;
    const node = audioCtx.createScriptProcessor(1024, 0, 2);
    node.onaudioprocess = (e) => {
      const l = e.outputBuffer.getChannelData(0);
      const r = e.outputBuffer.getChannelData(1);
      for (let i = 0; i < l.length; i++){
        if (rd !== wr){ l[i] = aL[rd]; r[i] = aR[rd]; rd = (rd + 1) & MASK; }
        else { l[i] = 0; r[i] = 0; }
      }
    };
    node.connect(gain); gain.connect(audioCtx.destination);
  }
  function resumeAudio(){ if (audioCtx && audioCtx.state === "suspended") audioCtx.resume(); }

  // --- jsnes ---
  let romLoaded = false, running = false;
  const nes = new jsnes.NES({
    onFrame: function(fb24){
      for (let i = 0; i < fb24.length; i++) fb_u32[i] = 0xff000000 | fb24[i];
    },
    onAudioSample: function(left, right){
      const next = (wr + 1) & MASK;
      if (next !== rd){ aL[wr] = left; aR[wr] = right; wr = next; }
    }
  });

  // --- sizing ---
  function applySize(){
    const caseSize = diameter + 34;
    watchEl.style.width = caseSize + "px";
    watchEl.style.height = caseSize + "px";
    screenEl.style.width = diameter + "px";
    screenEl.style.height = diameter + "px";
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(diameter * dpr);
    canvas.height = Math.round(diameter * dpr);
    canvas.style.width = diameter + "px";
    canvas.style.height = diameter + "px";
    dctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    dctx.imageSmoothingEnabled = false;
  }

  const BIAS_X = 0.50, BIAS_Y = 0.50;
  let debugOverlay = true;

  function gameRect(){
    const dw = diameter * 0.84;
    const dh = dw * (NH / NW);
    const dx = (diameter - dw) / 2;
    const dy = (diameter - dh) / 2 - diameter * 0.01;
    return { dx, dy, dw, dh };
  }
  const CORNER_R = 0;

  function clipGame(dx, dy, dw, dh){
    dctx.save();
    dctx.beginPath();
    dctx.roundRect(dx, dy, dw, dh, CORNER_R);
    dctx.clip();
  }

  function drawDebugGrid(){
    const { dx, dy, dw, dh } = gameRect();
    clipGame(dx, dy, dw, dh);
    dctx.fillStyle = "#8bac0f";
    dctx.fillRect(dx, dy, dw, dh);
    dctx.strokeStyle = "rgba(48,72,0,.12)";
    dctx.lineWidth = 1;
    const step = dw / 20;
    for (let i = 0; i <= 20; i++){
      const x = dx + i * step;
      dctx.beginPath(); dctx.moveTo(x, dy); dctx.lineTo(x, dy + dh); dctx.stroke();
    }
    const stepY = dh / 18;
    for (let i = 0; i <= 18; i++){
      const y = dy + i * stepY;
      dctx.beginPath(); dctx.moveTo(dx, y); dctx.lineTo(dx + dw, y); dctx.stroke();
    }
    dctx.restore();
    dctx.fillStyle = "#306230";
    dctx.font = "10px 'Press Start 2P', monospace";
    dctx.textAlign = "center";
    dctx.fillText("WatchEmu", diameter / 2, dy + dh / 2);
  }
  function draw(){
    dctx.clearRect(0, 0, diameter, diameter);
    if (!romLoaded){ if (debugOverlay) drawDebugGrid(); return; }
    bctx.putImageData(image, 0, 0);
    const sw = NW / zoom, sh = NH / zoom;
    const sx = Math.max(0, Math.min((NW - sw) * BIAS_X, NW - sw));
    const sy = Math.max(0, Math.min((NH - sh) * BIAS_Y, NH - sh));
    const { dx, dy, dw, dh } = gameRect();
    clipGame(dx, dy, dw, dh);
    dctx.imageSmoothingEnabled = false;
    dctx.drawImage(buf, sx, sy, sw, sh, dx, dy, dw, dh);
    dctx.restore();
  }

  // --- main loop (cap ~60 fps NTSC) ---
  const STEP = 1000 / 60.0988;
  let last = performance.now(), acc = 0, fcount = 0, ft = performance.now();
  function loop(now){
    requestAnimationFrame(loop);
    const dt = now - last; last = now;
    if (running && romLoaded){
      acc += dt; let ran = 0;
      while (acc >= STEP && ran < 4){ nes.frame(); acc -= STEP; ran++; fcount++; }
      if (acc > 200) acc = 0;
    }
    draw();
    if (now - ft >= 500){ fpsEl.textContent = Math.round(fcount / ((now - ft)/1000)); fcount = 0; ft = now; }
  }
  requestAnimationFrame(loop);

  // --- input mapping ---
  const C = jsnes.Controller;
  const press = (btn) => nes.buttonDown(1, btn);
  const release = (btn) => nes.buttonUp(1, btn);

  function bind(el, btn){
    const down = (e) => {
      e.preventDefault(); resumeAudio();
      try { el.setPointerCapture(e.pointerId); } catch (_) {}
      el.classList.add("on"); press(btn);
    };
    const up = (e) => {
      if (e) { e.preventDefault(); try { el.releasePointerCapture(e.pointerId); } catch (_) {} }
      el.classList.remove("on"); release(btn);
    };
    el.addEventListener("pointerdown", down);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
    el.addEventListener("contextmenu", (e) => e.preventDefault());
    return { el, btn, up };
  }
  const ctrls = {
    a:      bind(document.getElementById("btnA"),      C.BUTTON_A),
    b:      bind(document.getElementById("btnB"),      C.BUTTON_B),
    start:  bind(document.getElementById("btnStart"),  C.BUTTON_START),
    select: bind(document.getElementById("btnSelect"), C.BUTTON_SELECT),
  };

  // --- analog stick ---
  (function analogStick(){
    const el = document.getElementById("analog");
    const stick = document.getElementById("analogStick");
    const DEAD = 0.25;
    let active = false, cx = 0, cy = 0;
    let dirX = 0, dirY = 0;

    function setDir(newX, newY){
      if (newX !== dirX){
        if (dirX === -1) release(C.BUTTON_LEFT);
        if (dirX ===  1) release(C.BUTTON_RIGHT);
        dirX = newX;
        if (dirX === -1) press(C.BUTTON_LEFT);
        if (dirX ===  1) press(C.BUTTON_RIGHT);
      }
      if (newY !== dirY){
        if (dirY === -1) release(C.BUTTON_UP);
        if (dirY ===  1) release(C.BUTTON_DOWN);
        dirY = newY;
        if (dirY === -1) press(C.BUTTON_UP);
        if (dirY ===  1) press(C.BUTTON_DOWN);
      }
    }

    function update(ex, ey){
      const r = el.getBoundingClientRect();
      const radius = r.width / 2;
      let dx = ex - cx, dy = ey - cy;
      const dist = Math.sqrt(dx * dx + dy * dy);
      const maxDist = radius - 8;
      if (dist > maxDist){ dx = dx / dist * maxDist; dy = dy / dist * maxDist; }
      const norm = Math.min(dist / maxDist, 1);
      stick.style.left = (radius + dx) + "px";
      stick.style.top = (radius + dy) + "px";
      if (norm > DEAD){
        const angle = Math.atan2(dy, dx);
        const nx = Math.cos(angle) > 0.4 ? 1 : (Math.cos(angle) < -0.4 ? -1 : 0);
        const ny = Math.sin(angle) > 0.4 ? 1 : (Math.sin(angle) < -0.4 ? -1 : 0);
        setDir(nx, ny);
      } else {
        setDir(0, 0);
      }
    }

    el.addEventListener("pointerdown", (e) => {
      e.preventDefault(); resumeAudio();
      active = true;
      const r = el.getBoundingClientRect();
      cx = r.left + r.width / 2;
      cy = r.top + r.height / 2;
      el.classList.add("active");
      try { el.setPointerCapture(e.pointerId); } catch (_) {}
      update(e.clientX, e.clientY);
    });
    el.addEventListener("pointermove", (e) => { if (active) update(e.clientX, e.clientY); });
    function end(e){
      if (!active) return; active = false;
      try { el.releasePointerCapture(e.pointerId); } catch (_) {}
      el.classList.remove("active");
      setDir(0, 0);
      stick.style.left = "50%";
      stick.style.top = "50%";
    }
    el.addEventListener("pointerup", end);
    el.addEventListener("pointercancel", end);
    el.addEventListener("contextmenu", (e) => e.preventDefault());
  })();

  // teclado
  const KEYBTN = {
    "f": C.BUTTON_A, "g": C.BUTTON_B,
    "l": C.BUTTON_RIGHT, "arrowright": C.BUTTON_RIGHT,
    "a": C.BUTTON_LEFT, "arrowleft": C.BUTTON_LEFT,
    "w": C.BUTTON_UP, "arrowup": C.BUTTON_UP,
    "s": C.BUTTON_DOWN, "arrowdown": C.BUTTON_DOWN,
    "enter": C.BUTTON_START, "v": C.BUTTON_SELECT,
  };
  const KEYEL = {
    "f": ctrls.a.el, "g": ctrls.b.el,
    "enter": ctrls.start.el, "v": ctrls.select.el,
  };
  const held = {};
  window.addEventListener("keydown", (e) => {
    const key = e.key.toLowerCase();
    const btn = KEYBTN[key]; if (btn === undefined) return;
    e.preventDefault();
    if (held[key]) return; held[key] = true;
    resumeAudio(); press(btn);
    const el = KEYEL[key]; if (el) el.classList.add("on");
  });
  window.addEventListener("keyup", (e) => {
    const key = e.key.toLowerCase();
    const btn = KEYBTN[key]; if (btn === undefined) return;
    held[key] = false; release(btn);
    const el = KEYEL[key]; if (el) el.classList.remove("on");
  });

  // --- ROM loading + diagnostico iNES ---
  function inspectROM(bytes, name){
    const magicOK = bytes.length >= 16 &&
      bytes[0]===0x4E && bytes[1]===0x45 && bytes[2]===0x53 && bytes[3]===0x1A;
    const prg = bytes[4], chr = bytes[5], f6 = bytes[6], f7 = bytes[7];
    const mapper = ((f7 & 0xF0) | (f6 >> 4));
    const mirroring = (f6 & 8) ? "4-screen" : ((f6 & 1) ? "vertical" : "horizontal");
    const trainer = !!(f6 & 4);
    const ines2 = ((f7 & 0x0C) === 0x08);
    const rows = [];
    rows.push(["Arquivo", name]);
    rows.push(["Tamanho", bytes.length.toLocaleString("pt-BR") + " bytes"]);
    rows.push(["Assinatura NES", magicOK ? ["OK","ok"] : ["ausente","bad"]]);
    if (magicOK){
      rows.push(["PRG-ROM", prg + " x 16 KB = " + (prg*16) + " KB"]);
      rows.push(["CHR-ROM", chr + " x 8 KB = " + (chr*8) + " KB"]);
      rows.push(["Mapper", String(mapper) + (mapper===0 ? " (NROM)" : "")]);
      rows.push(["Espelhamento", mirroring]);
      if (trainer) rows.push(["Trainer", ["presente — 512 b extra","bad"]]);
      if (ines2) rows.push(["Formato", "iNES 2.0"]);
    }
    let verdict, cls;
    if (!magicOK){
      cls = "bad";
      verdict = "Isto nao parece um .nes valido — faltam os bytes NES no inicio. Provavel arquivo corrompido ou que nem e de NES.";
    } else if (mapper===0 && prg===2 && chr===1){
      cls = "good";
      verdict = "ROM sa: NROM classico, classe Super Mario Bros (32 KB PRG + 8 KB CHR, mapper 0). Se ainda nao anda, aperte START pra sair da demo e entao segure o -> (L).";
    } else if (mapper!==0){
      cls = "warn";
      verdict = "Cabecalho valido, mas mapper " + mapper + " (nao e o NROM do Mario). O jsnes so cobre alguns mappers; se a tela vier com graficos quebrados, e a ROM/mapper.";
    } else {
      cls = "warn";
      verdict = "Cabecalho valido, porem PRG/CHR fora do padrao do SMB original — pode ser um hack ou outro jogo.";
    }
    return { rows, verdict, cls };
  }
  function renderDiag(){}
  function showDiagError(msg){
    romLoaded = false; running = false; hint.classList.add("show");
    hint.innerHTML = "ROM nao carregou.<br>" + msg;
  }
  function loadNESBytes(bytes, name){
    renderDiag(inspectROM(bytes, name));
    try {
      let s = ""; const CH = 0x8000;
      for (let i = 0; i < bytes.length; i += CH)
        s += String.fromCharCode.apply(null, bytes.subarray(i, i + CH));
      nes.loadROM(s);
      romLoaded = true; running = true;
      hint.classList.remove("show");
      initAudio(); resumeAudio();
    } catch (err) {
      romLoaded = false; running = false;
      hint.classList.add("show");
      hint.innerHTML = "ROM nao carregou.<br>Veja o diagnostico abaixo";
      const ver = document.getElementById("verdict");
      ver.className = "verdict bad";
      ver.textContent = "O jsnes recusou a ROM: " + (err && err.message ? err.message : err) + ". Quase sempre e dump corrompido ou mapper nao suportado.";
    }
  }

  const fileInput = document.getElementById("file");
  document.getElementById("romBtn").addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", (e) => {
    const f = e.target.files[0]; if (!f) return;
    const reader = new FileReader();
    reader.onload = () => {
      let bytes = new Uint8Array(reader.result);
      let name = f.name;
      const isZip = (bytes[0] === 0x50 && bytes[1] === 0x4B) || /\.zip$/i.test(f.name);
      if (isZip){
        if (typeof fflate === "undefined"){ showDiagError("Suporte a .zip indisponivel nesta pagina."); return; }
        try {
          const files = fflate.unzipSync(bytes);
          const names = Object.keys(files).filter((n) => !n.endsWith("/"));
          const entry = names.find((n) => /\.nes$/i.test(n));
          if (!entry){
            showDiagError("O .zip abriu, mas nao ha nenhum arquivo .nes dentro" +
              (names.length ? " (achei: " + names.map((n)=>n.split("/").pop()).join(", ") + ")." : "."));
            return;
          }
          bytes = files[entry];
          name = f.name + "  ->  " + entry.split("/").pop();
        } catch (err) {
          showDiagError("Nao consegui abrir o .zip: " + (err && err.message ? err.message : err));
          return;
        }
      }
      loadNESBytes(bytes, name);
    };
    reader.readAsArrayBuffer(f);
  });


  applySize();
  window.addEventListener("resize", applySize);
})();
