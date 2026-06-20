# WatchEmu

**NES & SNES emulator designed for smartwatches**
**Emulador NES & SNES projetado para smartwatches**

---

## English

### About

WatchEmu is a web-based NES and SNES emulator with a UI designed specifically for round smartwatches (Wear OS). The interface simulates a real watch form factor, placing game controls in the crescent-shaped areas between the rectangular game screen and the circular watch border.

### Features

- **NES Emulation** — Pure JavaScript emulation via jsnes, no plugins required
- **SNES Emulation** — Powered by EmulatorJS with automatic WASM core download
- **Watch-optimized controls** — A/B buttons in top crescent (index finger), analog stick in bottom crescent (thumb), SELECT/START on side crescents
- **Draggable analog stick** — Full 8-direction input with dead zone and diagonal support
- **Game Boy-style idle screen** — Classic green screen when no ROM is loaded
- **Switch Lite aesthetic** — Warm yellow/gold color scheme with cartoon-style buttons
- **Physical ROM pusher** — Watch-style hardware button to load ROMs
- **Rounded game screen** — Canvas clipping with rounded corners for watch aesthetics
- **ZIP support** — Load ROMs directly from .zip files
- **ROM diagnostics** — iNES header analysis with mapper detection and compatibility warnings
- **Multiple watch models** — Presets for Fossil Gen 5 (454px), TicWatch/Pixel (416px), Galaxy Watch (396px)
- **Zoom & fit modes** — Adjustable camera zoom (1x, 1.5x, 2x) and fill/fit options
- **Audio support** — NES audio output via Web Audio API

### Controls

| Control | Location | Input |
|---------|----------|-------|
| A | Top crescent (left) | Touch / Key F |
| B | Top crescent (right) | Touch / Key G |
| D-pad | Bottom crescent (analog stick) | Drag / Arrow keys |
| SELECT | Left crescent | Touch / Key V |
| START | Right crescent | Touch / Enter |
| Load ROM | Physical pusher (right side) | Click |

### How to Run

1. Serve the project with any local HTTP server:
   ```bash
   # Python
   python -m http.server 8080

   # Node.js
   npx serve .

   # VS Code Live Server extension
   ```
2. Open `http://localhost:8080` in your browser
3. Select NES or SNES
4. Load a ROM file (.nes, .smc, or .zip)

> **Note:** The project must be served via HTTP, not opened directly as `file://`, because the SNES emulator fetches its WASM core from a CDN.

### Project Structure

```
watchEmu/
  index.html              — NES/SNES selector
  shared/
    watch-ui.css           — Shared CSS (watch visual, controls, Switch Lite theme)
    home.css               — Home page styles
    fflate.min.js          — ZIP decompression library
  nes/
    index.html             — NES standalone page
    jsnes.min.js           — NES emulation core
    nes-app.js             — NES app logic (rendering, input, audio, ROM loading)
  snes/
    index.html             — SNES standalone page
    snes-app.js            — SNES app logic + EmulatorJS integration
    snes.css               — SNES-specific styles
```

---

## Portugues

### Sobre

WatchEmu e um emulador NES e SNES baseado em web com interface projetada especificamente para smartwatches redondos (Wear OS). A interface simula o formato real de um relogio, posicionando os controles nas areas em forma de crescente entre a tela retangular do jogo e a borda circular do relogio.

### Funcionalidades

- **Emulacao NES** — Emulacao em JavaScript puro via jsnes, sem plugins
- **Emulacao SNES** — Atraves do EmulatorJS com download automatico do core WASM
- **Controles otimizados para relogio** — Botoes A/B no crescente superior (dedo indicador), analogico no crescente inferior (polegar), SELECT/START nos crescentes laterais
- **Analogico arrastavel** — Entrada em 8 direcoes com zona morta e suporte a diagonais
- **Tela idle estilo Game Boy** — Tela verde classica quando nenhuma ROM esta carregada
- **Estetica Switch Lite** — Esquema de cores amarelo/dourado quente com botoes estilo cartoon
- **Pusher fisico de ROM** — Botao estilo hardware de relogio para carregar ROMs
- **Tela de jogo arredondada** — Recorte do canvas com bordas arredondadas
- **Suporte a ZIP** — Carregue ROMs diretamente de arquivos .zip
- **Diagnostico de ROM** — Analise do cabecalho iNES com deteccao de mapper e avisos de compatibilidade
- **Multiplos modelos de relogio** — Presets para Fossil Gen 5 (454px), TicWatch/Pixel (416px), Galaxy Watch (396px)
- **Modos de zoom e ajuste** — Zoom de camera ajustavel (1x, 1.5x, 2x) e opcoes de preenchimento/ajuste
- **Suporte a audio** — Saida de audio NES via Web Audio API

### Controles

| Controle | Localizacao | Entrada |
|----------|-------------|---------|
| A | Crescente superior (esquerda) | Toque / Tecla F |
| B | Crescente superior (direita) | Toque / Tecla G |
| D-pad | Crescente inferior (analogico) | Arrastar / Setas |
| SELECT | Crescente esquerdo | Toque / Tecla V |
| START | Crescente direito | Toque / Enter |
| Carregar ROM | Pusher fisico (lado direito) | Clique |

### Como Executar

1. Sirva o projeto com qualquer servidor HTTP local:
   ```bash
   # Python
   python -m http.server 8080

   # Node.js
   npx serve .

   # Extensao Live Server do VS Code
   ```
2. Abra `http://localhost:8080` no navegador
3. Selecione NES ou SNES
4. Carregue um arquivo ROM (.nes, .smc ou .zip)

> **Nota:** O projeto deve ser servido via HTTP, nao aberto diretamente como `file://`, pois o emulador SNES busca seu core WASM de um CDN.

---

## Credits / Creditos

### Emulation Cores / Cores de Emulacao

- **[jsnes](https://github.com/bfirsh/jsnes)** by Ben Firshman — NES emulator in JavaScript. MIT License.
- **[EmulatorJS](https://emulatorjs.org/)** ([GitHub](https://github.com/EmulatorJS/EmulatorJS)) — Browser-based emulation platform using RetroArch cores compiled to WebAssembly. GPLv3 License.
- **[RetroArch](https://www.retroarch.com/)** / [Libretro](https://www.libretro.com/) — Multi-platform emulation framework. The SNES core (Snes9x) is loaded via EmulatorJS. GPLv3 License.
- **[Snes9x](https://github.com/snes9xgit/snes9x)** — SNES emulator used as the RetroArch core for SNES emulation. Non-commercial license.

### Libraries / Bibliotecas

- **[fflate](https://github.com/101arrowz/fflate)** by Arjun Barrett — High-performance JavaScript compression/decompression library used for ZIP ROM loading. MIT License.

### Technologies / Tecnologias

- **HTML5 Canvas** — Game rendering with rounded clipping and pixel-perfect scaling
- **Web Audio API** — NES audio output via ScriptProcessor
- **Pointer Events API** — Touch input handling for analog stick and buttons
- **WebAssembly** — SNES emulation core execution (via EmulatorJS CDN)

### Design Inspiration / Inspiracao de Design

- **Nintendo Switch Lite** — Color scheme and button aesthetic
- **Nintendo Game Boy** — Idle screen visual style
- **Wear OS / Smartwatch UI** — Crescent-based control layout optimized for round displays

---

## License / Licenca

This project is provided for educational and personal use. No ROMs are included. You must provide your own legally obtained ROM files.

Este projeto e fornecido para uso educacional e pessoal. Nenhuma ROM esta incluida. Voce deve fornecer seus proprios arquivos ROM obtidos legalmente.

---

*Made with passion for retro gaming on tiny screens.*
*Feito com paixao por jogos retro em telas minusculas.*
