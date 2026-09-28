# NEXUS VIDEO — histórico

Projeto criado em 25/09/2026, reaproveitando a base e o tema do NEXUS MUSIC 2.
App **exclusivo para VÍDEOS**, com opinião de qualidade antes de baixar.

---

## v1.0 — primeira versão — 25/09/2026

**Pedido do Leo:** *"Criar novo projeto usando toda a base do Nexus, tema, o que for possível.
Nova pasta NEXUS VÍDEO. Usar mesma lógica YouTube download. Será exclusivo para baixar vídeos,
dar opiniões de qualidade do vídeo disponível para download."*

### Descoberta que definiu o projeto

Medido com o extrator real (NewPipeExtractor v0.26.5) em vídeos do YouTube:

| Formato | Streams oferecidos |
|---|---|
| Progressivo (vídeo+áudio juntos) | **1 só: 360p** |
| Adaptativos (só imagem) | 2160p, 1440p, 1080p, 720p, 480p, 360p, 240p, 144p |
| Áudio | M4A 48/128 kbps, Opus 50/70/160 kbps |

**Conclusão:** acima de 360p o YouTube **sempre** entrega imagem e som separados. Para oferecer
720p/1080p/4K é obrigatório juntar os dois arquivos depois do download.

### Decisão técnica: juntar sem ffmpeg

`MediaExtractor` + `MediaMuxer` (APIs do Android) fazem a junção **copiando as amostras**, sem
reencodar. Não foi preciso empacotar ffmpeg nem adicionar AAR — o APK continua leve (1,87 MB).
Pré-requisito verificado: o vídeo adaptativo é **H.264** e o áudio é **AAC**, exatamente o que o
`MediaMuxer` aceita.

### O que foi reaproveitado do NEXUS MUSIC 2

- **Tema completo:** fontes Orbitron/Rajdhani, `fundo.jpg`, vidro (`blur(8px)`), aro metálico,
  cards 170×170 equivalentes, rodapé, ausência de realce azul/seleção.
- **Efeito plexus** no visor (20 pontos, colisão nas bordas).
- **Lógica de download comprovada:** tamanho por `Range: bytes=0-0` + 5 conexões paralelas,
  **sem validar cabeçalho de cada parte** — a validação foi o que travou o outro app 3 vezes.
- **Arquitetura:** WebView + servidor HTTP local + ponte JS, `MediaStore`, Range para o player.

### Arquivos criados

| Arquivo | Papel |
|---|---|
| `MainActivity.java` | WebView, tela cheia do player, permissões, link compartilhado |
| `NexusServer.java` | HTTP local (8577-8579), rotas da UI/API/vídeo/miniatura |
| `VideoLibrary.java` | MediaStore de vídeos, pastas, miniaturas (`loadThumbnail`) |
| `YtVideoDownload.java` | pesquisa, qualidades, download 5 conexões, junção |
| `QualidadeVideo.java` | lógica pura das opções e das notas (testável sem Android) |
| `assets/index.html` | interface com tema NEXUS, abas VÍDEOS/YOUTUBE, player, qualidades |

### Verificações

- **Testes puros** de `QualidadeVideo`: **17 verificações aprovadas** (montagem, ordenação,
  preferência por arquivo único em empate, `alturaDe` com "1080p60", listas vazias, webm/mp4).
- **Download real:** 360p 12.146.162 B ✔ · 720p 24.457.601 B ✔ · áudio 4.109.698 B ✔.
- **Junção:** MP4 final com vídeo H.264 986×720 + áudio AAC 44,1 kHz 2ch, 253,89 s, sem reencode ✔.
- **Interface 412×915:** biblioteca, vídeo tocando (494×360, `currentTime` avançando), busca com
  10 resultados, qualidades com 8 opções + notas, zero exceções JS.
- `./gradlew clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`, **Lint sem issues**
  (corrigido um erro real `WrongConstant`: flags de amostra do `MediaExtractor` sendo passados
  direto ao `BufferInfo`; agora traduzidos para `BUFFER_FLAG_KEY_FRAME`).
- APK `NEXUS-VIDEO-v1.0-primeira-versao.apk`; `versionCode 1`; `versionName 1.0`;
  1.878.507 bytes; SHA-256 `4cfef1f1dc76b5486ff6a581466fddee2f1ee706ebcb65f88d991a4c6e659841`.
- Assinatura v2/v3 com **chave própria** (`nexusvideo-release.jks`); pacote e Activity corretos;
  `assets/index.html` fonte == empacotado.

### Ajuste durante o desenvolvimento

O plexus estava desenhado **por cima** do vídeo. Corrigido com `z-index:2` no `<video>` e `1` no
canvas — a animação fica atrás da imagem. Confirmado por medição (`getComputedStyle`).

### Pendências

- `MediaMuxer` só existe no Android: a junção foi provada por operação equivalente (ffmpeg `-c copy`),
  mas **não executada em aparelho** (não há `adb` nesta máquina).
- Miniatura exige Android 10+ (`loadThumbnail`); antes disso o card fica sem imagem.
- Favoritos não foram portados nesta versão.

---

## v1.1 — correção de build — 26/09/2026

- Corrigido erro de compilação em `YtVideoDownload.juntar`: o método era `static` e acessava o estado de cancelamento (`cancelarSolicitado`) da instância. Tornado método de instância (`private void`); o chamador já era instancial.
- Validação: `scripts/validar-interface.sh` passou; `testReleaseUnitTest lintRelease` e `clean assembleRelease lintRelease` concluíram com `BUILD SUCCESSFUL`. Não há fontes de teste unitário neste projeto (`testReleaseUnitTest NO-SOURCE`). O Lint terminou com **9 avisos** não bloqueantes: JavaScript habilitado no WebView (2), regras de extração de dados, formato do ícone (5) e texto Android hardcoded.
- Release gerada em `app/build/outputs/apk/release/app-release.apk` e publicada nesta pasta como `NEXUS-VIDEO-v1.1-correcao-build-release.apk`.
- Identidade: `com.leo.nexusvideo`, `versionCode 2`, `versionName 1.1`, Activity `com.leo.nexusvideo.MainActivity`. ZIP íntegro, alinhamento aprovado e assinatura v2/v3 válida. O certificado SHA-256 continua igual ao da v1.0: `445199a7b52c11c2b5aaeaeae556f6ceb0d596f94a8ddf984ac2972c57b5e6ce`.
- Prova do asset: SHA-256 da fonte e de `assets/index.html` no APK = `fdbddc41d76f9c78c7d57a50f77ef48c82fa5a7c1d6191bcc417b9b7d75df7d2`.
- APK: 1.881.912 bytes; SHA-256 `adec66285ace729a8a8aaa6591df3bf791227c34d8c374c3e88d726e6660d44a`.
- Limite de validação: não havia aparelho conectado (ADB sem dispositivos) nem emulador disponível; instalação, reprodução e junção MediaMuxer em runtime no Android permanecem sem teste físico. O APK e sua correção de compilação foram verificados localmente.

---

## v1.2 — prévia com vídeo e download integrado ao card — 26/09/2026

**Relato do Leo:** ao selecionar um resultado para prévia, só o som saía (imagem ausente); pediu também que qualidades e barra de progresso ficassem dentro do card correspondente.

### Correções

- A prévia usava a URL da faixa de áudio isolada (`getAudioStreams()`), que não contém imagem. Agora resolve somente um `VideoStream` progressivo (`!isVideoOnly()`), preferindo maior resolução e MP4 em empate; a prévia reproduz vídeo+áudio no `<video>` já visível.
- Mantido o proxy local com suporte a `Range`; rota `/api/youtube/media` aceita o stream audiovisual para o WebView.
- Cada resultado agora inclui uma área expansível própria. O botão ⬇ abre as qualidades naquele card; ao escolher resolução, percentual/etapa/barra atualizam ali sem remover resultados vizinhos. Pesquisa/seletores ficam bloqueados enquanto há download para evitar concorrência; progresso genérico global fica oculto no fluxo por card.
- Versão subida para `versionCode 3` / `versionName 1.2` e ponte nativa de versão atualizada para 1.2.

### Verificação

- `scripts/validar-interface.sh`: JS válido; CSS balanceado.
- `testReleaseUnitTest lintRelease` e `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`; testes unitários não existem (`NO-SOURCE`); Lint com 9 avisos não bloqueantes.
- Harness Chromium por HTTP em 412×915, MP4 de teste gerado com vídeo+áudio: 640×360 decodificado, `currentTime` avançou, player não pausado; painel 720p/360p dentro do primeiro card; barra observada em 22% e conclusão a 100% dentro desse mesmo card; ambos os resultados permaneceram no DOM; nenhuma exceção JS.
- Limite: harness simula as respostas de `/api/youtube/previa`, `/qualidades` e download; não consulta YouTube/NewPipe em tempo real. Sem aparelho/emulador, o comportamento final precisa ainda ser confirmado no WebView Android físico.
- Release `NEXUS-VIDEO-v1.2-previa-video-download-no-card-release.apk`; pacote/Activity corretos, assinatura v2/v3 com certificado igual às releases anteriores, ZIP/alinhamento aprovados, `assets/index.html` empacotado igual à fonte. SHA-256 HTML `bc5a29640f470be590c4fa3c6bc7f285eabb3bc5bfdb1402e8fcc07911ea3189`; APK 1.883.138 bytes, SHA-256 `06b08f1ab29b924d95c7135e60cb9838d3fda9db40b230bf2e416804fd4c38b2`.

---

## v1.3 — refinamento e fechamento da prévia/download no card — 26/09/2026

- Refinado o estado do card: sem mensagem redundante de status fora do resultado durante seleção de qualidade/download, apenas um seletor de qualidade expandido por vez, e parar/cancelar uma prévia não apaga nem pausa incorretamente a reprodução da biblioteca.
- Harness CDP final (HTTP/Chromium, viewport 412×915): prévia MP4 com imagem+som, 640×360, `currentTime` avançou e player permaneceu tocando; qualidades dentro do resultado; download observado em 22% dentro do card selecionado, 2 resultados preservados, barra global oculta e status externo vazio; conclusão 100%, botões reabilitados e zero exceções.
- `scripts/validar-interface.sh`, `testReleaseUnitTest lintRelease` e `clean assembleRelease lintRelease`: aprovados. `testReleaseUnitTest` é `NO-SOURCE`; Lint terminou com 9 avisos não bloqueantes. Aviso de compatibilidade AGP 8.1.0 / compileSdk 34 não bloqueou o build.
- Release: `NEXUS-VIDEO-v1.3-previa-e-progresso-no-card-release.apk`; `versionCode 4`, `versionName 1.3`, pacote `com.leo.nexusvideo`, Activity `com.leo.nexusvideo.MainActivity`; assinatura v2/v3; certificado SHA-256 igual à v1.2: `445199a7b52c11c2b5aaeaeae556f6ceb0d596f94a8ddf984ac2972c57b5e6ce`; ZIP íntegro e alinhamento verificado.
- `assets/index.html` fonte/empacotado SHA-256 `9397b6f934085c6f48f7d8e20c0f241cd23ee9a76bbe2d9282bfc26b54a394b1`. APK 1.883.138 bytes; SHA-256 `48d559256da9219f91d8b65edeee77fbd86283d70ff87d16e9044687967bc0cd`.
- Nenhum Android/emulador conectado: harness simula a API do YouTube/download e valida a interface, mas NewPipe real, proxy no WebView instalado e reprodução no aparelho ainda carecem de teste físico.

---

## v1.4 — UI limpa, pop-up de qualidades, autoplay e biblioteca só do app — 26/09/2026

**Pedidos do Leo:** página principal deve reconhecer só a pasta dos downloads; área de download estava poluída; o botão BAIXAR deve abrir pop-up apenas com as qualidades e depois a barra de progresso fica junto ao card; após baixar, entrar como autoplayer e reproduzir; visor não pode ficar preto ao abrir; botões VÍDEOS/YOUTUBE preto com texto branco e borda branca 2px.

### Mudanças

- `VideoLibrary.recarregar()` filtra o MediaStore: `RELATIVE_PATH LIKE 'Movies/NexusVideo/%'` (API 29+) e `DATA LIKE '%Movies/NexusVideo/%'` antes disso. Só o conteúdo baixado pelo app entra na listagem.
- Removidos da interface: card de download separado, barra de progresso global e o painel de qualidades embutido. Cada resultado é um card único com `▶ PRÉVIA` e `⬇ BAIXAR`.
- `⬇ BAIXAR` abre o modal `#modalQualidades`, que exibe somente resolução + selo (RECOMENDADA / ARQUIVO ÚNICO). A escolha fecha o modal e inicia o download.
- Progresso (`percentual`, etapa, barra) renderizado dentro do card escolhido; mensagens de status externas ficam vazias nesse fluxo.
- Ao concluir: `tocarBaixado()` troca para a aba VÍDEOS, recarrega a biblioteca e toca o vídeo mais recente. O botão ▶ central também passou a funcionar quando o que está carregado é a prévia.
- `carregarUltimoNoVisor()` deixa o vídeo mais recente carregado no player ao abrir o app (pausado).
- Abas e botões de ação: fundo preto, texto branco, borda branca 2px; aba ativa inverte (fundo branco).
- Fundo natalino: imagem movida de `body::before` para `html` (o pseudo-elemento com `z-index:-1` ficava atrás do fundo do `body` e nunca era pintado). O arquivo é o original do Leo, sem tratamento.

### Verificação

- Harness CDP/HTTP em 412×915 com cinco estados capturados: card com os dois botões; pop-up com 2 opções; progresso em 22% dentro do card e modal fechado; autoplay com `videoWidth=640`, `paused=false`, `currentTime` avançando. Zero exceções JS.
- Limite: o harness simula `/api/youtube/*` e `/api/library`; não exercita NewPipe, o proxy real no WebView nem o filtro de MediaStore do aparelho.
- `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`; Lint com 9 avisos não bloqueantes.
- APK `NEXUS-VIDEO-v1.4-ui-limpa-e-autoplay-release.apk`; `versionCode 5`, `versionName 1.4`; assinatura v2/v3, certificado SHA-256 igual às releases anteriores; ZIP/alinhamento aprovados. HTML empacotado == fonte (`1570cd11…`); `fundo.jpg` empacotado byte a byte (`0b15c98f…`). Marcadores no dex: `relative_path LIKE ?`, `_data LIKE ?`, `/NexusVideo/`.
- Tamanho 1.880.130 bytes; SHA-256 `948c91fbe2eed77bd166cd85eb1624c863196f90cd4c829c373ade68c89f1990`.

---

## v1.5 — visor com primeiro quadro — 26/09/2026

**Pedido do Leo:** "Tudo ok só faltou aplicar tela DP último vídeo no visor aparece tela cinza do player."

### Mudanças

- `carregarUltimoNoVisor()` passou a definir `player.poster` com a miniatura (`/thumb/<id>`) e a chamar `mostrarPrimeiroQuadro()`: no evento `loadeddata`, se `player.paused && previaIndice < 0 && currentTime <= 0.01`, busca `currentTime = 0.05` para forçar a decodificação do primeiro quadro.
- `tocarIndice()` também usa `poster` e o limpa quando a reprodução começa.

### Verificação

- Harness CDP com miniatura azul (`0x2255ff`) e vídeo `testsrc2` 640×360: `poster` ativo, `readyState=4`, `videoWidth=640`, `currentTime=0.05`, `paused=true`, zero exceções.
- Recorte central do visor: cores dominantes das barras de teste (`255,255,2` / `0,255,2` / `0,0,253` / `255,0,253`), média `(121,133,125)` — não é retângulo cinza.
- APK `NEXUS-VIDEO-v1.5-visor-com-quadro-release.apk`; `versionCode 6`, assinatura v2/v3, ZIP/alinhamento aprovados. HTML empacotado == fonte (`4351730b…`); fundo byte a byte (`0b15c98f…`). Tamanho 1.880.406 bytes; SHA-256 `30fd74cd672536c6fbd19aa36034ee3831c4fca5fa962724f950c9cee8f25745`.

---

## v1.6 — visor com miniatura estável — 26/09/2026

**Pedido do Leo:** "Abriu por um momento logo depôs voltou tela preta."

### Mudanças

- Causa: o WebView descarta o quadro decodificado quando não há reprodução ativa. O `poster` + `seek` da v1.5 dependia de um quadro apresentado que não se sustenta.
- Nova camada `<img class="visor-poster" id="visorPoster" hidden>` dentro de `.visor`, sobreposta ao `<video>` (`z-index: 3`), exibindo a miniatura do MediaStore (`/thumb/<id>`). `onerror` da imagem apenas esconde a camada — o visor nunca quebra.
- Funções `mostrarPoster(id)` / `esconderPoster()`; `carregarUltimoNoVisor()` usa a miniatura; `tocarIndice()` esconde.
- Handlers: `play` → esconde; `pause` → mostra a miniatura do vídeo selecionado; `pararPrevia()` (fim da prévia) → volta a mostrar.

### Verificação

- Harness CDP com miniatura roxa (`0x7a2bd6`) e três estados: parado → `posterVisivel:true`, cor `[121,44,212]` em 69,3% da área; tocando → `posterVisivel:false`, `paused:false`, `videoWidth:640`; pausado → miniatura de volta.
- Zero exceções JS; capturas A/B/C confirmam miniatura parada e barras de teste tocando.
- APK `NEXUS-VIDEO-v1.6-visor-com-miniatura-release.apk`; `versionCode 7`, assinatura v2/v3, ZIP/alinhamento aprovados. HTML empacotado == fonte (`8d6e9af3…`); fundo byte a byte (`0b15c98f…`). Tamanho 1.880.528 bytes; SHA-256 `7738d5ad991c3b86422dc8bb5ed86dffe44e055ab94477d3dff8e500cb6313c1`.

---

## v1.7 — topo central sem contador — 26/09/2026

**Pedido do Leo:** "No topo deixar nexus video centralizado e remover contador vidro do topo."

### Mudanças

- `.topo-linha` passou de `justify-content: space-between` para `justify-content: center` — o título "NEXUS VIDEO" fica centralizado.
- Removidos o markup `<div class="topo-contagem" id="contagem">000 VÍDEOS</div>`, o CSS `.topo-contagem` (pílula de vidro) e a linha de JS que escrevia em `#contagem`.
- O contador da lista (`#contadorBiblioteca`, "1 VÍDEO") permanece — a remoção foi só do topo.

### Verificação

- Medição em 412×915 via CDP: `.topo-logo` centro X = 206, `.topo` centro X = 206 → desvio **0 px**; `#contagem` e `.topo-contagem` ausentes do DOM; visor em `top: 44`, largura 412; zero exceções JS.
- Captura visual: título centralizado, sem pílula no cabeçalho; a pílula "1 VÍDEO" aparece apenas abaixo das abas.
- `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`. APK `NEXUS-VIDEO-v1.7-topo-central-release.apk`; `versionCode 8`, `versionName 1.7`; assinatura v2/v3; ZIP/alinhamento aprovados. HTML empacotado == fonte (`e259c4bc…`); fundo byte a byte (`0b15c98f…`). Tamanho 1.880.473 bytes; SHA-256 `8fedae115a22790c432f826722a27db90b56101e90fd1d7caaf50346e16f5358`.

---

## v1.8 — sem contador de vídeos e controles mais transparentes — 26/09/2026

**Pedidos do Leo:** "No topo deixar nexus video centralizado e remover contador video do topo" (repetido, apontando para o contador que ainda restava) e "Deixar box dos controles mais transparente."

### Mudanças

- Removida a pílula `.contador` ("1 VÍDEO") que ficava abaixo das abas: markup `<span id="contadorBiblioteca">`, o CSS `.contador` inteiro e a linha JS que escrevia nele. Com isso o `index.html` não tem mais **nenhuma** referência a contador de vídeos.
- `.ctl` (botões circular anterior/play/próximo): `background` de `rgba(16,16,16,.90)` para `rgba(16,16,16,.30)`, somado a `backdrop-filter: blur(7px)` e `-webkit-backdrop-filter: blur(7px)`. O botão fica vazado — a borda de 1px é que define o círculo.

### Verificação

- Medição CDP em 412×915: `#btnPlay` → `backgroundColor: rgba(16,16,16,0.30)`, `backdropFilter: blur(7px)`, borda `rgba(255,255,255,0.8)`; nenhum elemento com contador no DOM; `.topo-logo` com desvio de centralização 0 px; zero exceções JS.
- Varredura de todos os elementos com fundo/borda na faixa dos controles (y 300–430) confirma que `.ctl` são os únicos "boxes" ali.
- `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`. APK `NEXUS-VIDEO-v1.8-controles-transparentes-release.apk`; `versionCode 9`, `versionName 1.8`; assinatura v2/v3; ZIP/alinhamento aprovados. HTML empacotado == fonte (`d1dd6125…`); fundo byte a byte (`0b15c98f…`). Tamanho 1.880.406 bytes; SHA-256 `fbbd6617db2fe7ad4a206646c8a094068df82074acd83183606f4bf6949302e6`.

---

## v1.9 — tema black cyber (detalhes em degradê rosa → verde) — 26/09/2026

**Pedido do Leo:** "Modificar apenas cor tema black cyber detalhes degrade rosa e verde."

### Mudanças (somente cor — nada de layout)

- Paleta `#ff2f9e` (rosa) → `#2bffa8` (verde), em `linear-gradient(135deg,…)` para aros/preenchimentos de botão e `(90deg,…)` para textos e barras.
- Aros em degradê: visor, `.ctl`, `.aba`, `.vcard`, `.yt-resultado-item`, `.modal-caixa`, `.modal-fechar`, `.yt-acao` — todos com `border:1px solid transparent` + `linear-gradient(...) padding-box, linear-gradient(...) border-box`.
- Textos em degradê: `.topo-logo`, `.yt-titulo`, `.rodape-marca` (`background-clip:text` + `-webkit-text-fill-color:transparent`).
- Preenchimentos: aba ativa, `.yt-acao-previa.tocando`, `.qopc.recomendada .qopc-tag`, barras de progresso.
- Brilhos: `.ctl.principal` (rosa+verde), `.qopc.recomendada` (halo rosa).
- Malha do plexus interpolada de rosa a verde pelo eixo X.
- Estados: `.ok` → `#2bffa8`; `.erro` → `#ff4f9e`.
- Fundo `fundo.jpg` intocado; só `body::after` ganhou radiais discretos rosa/verde.

### Verificação

- Estilos computados conferidos no CDP: `backgroundImage` dos aros contém `rgb(255,47,158), rgb(43,255,168)`; textos com `webkitTextFillColor: rgba(0,0,0,0)`.
- Contagem de pixels na faixa do `.yt-titulo`: 65 rosa + 133 verde — degradê visível de fato.
- `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`. APK `NEXUS-VIDEO-v1.9-tema-black-cyber-release.apk`; `versionCode 10`, `versionName 1.9`; assinatura v2/v3; ZIP/alinhamento aprovados. HTML empacotado == fonte (`b522f91c…`); fundo byte a byte (`0b15c98f…`). Tamanho 1.880.659 bytes; SHA-256 `68d8007c9cba414dabea37ce4c1c9d6637eda395654a8f448b4f83d9685d502b`.

## v2.0 — player a 60 FPS (26/09/2026)

- **Sintoma:** o player engasgava com o vídeo baixado; outro app rodava o mesmo arquivo liso.
- **Causa raiz:** `backdrop-filter:blur(8px)` no `.topo`, que é **ancestral do `<video>`**.
  O WebView refazia o desfoque a cada quadro → 33–42 FPS e 26–32 % de frames dropados.
- **Correção:** `.topo.tocando{backdrop-filter:none}`; `atualizarBotaoPlay()` chama
  `topoReproduzindo()` — o vidro do painel sai só durante a reprodução. Visual parado idêntico.
- **Descartados por medição:** canvas PLEXUS em rAF, blur do `.conteudo`, barra por `width`,
  `will-change`, raios menores de blur, blur em `.topo::before`.
- **Resultado:** 59,8–60,2 FPS, 0–5 frames dropados, console limpo.
- **Build:** `versionCode 11 / 2.0` — `NEXUS-VIDEO-v2.0-player-fluidez-release.apk`
  (1.880.975 B; SHA-256 `c93e110e…`); `fundo.jpg` intacto `0b15c98f…`.

## v2.1 — remoção do PLEXUS invisível (26/09/2026)

- **Pedido do Leo:** “não existe plexus neste projeto” / “eliminar plexus”. O canvas herdado ficava atrás do vídeo e da miniatura, portanto não era visto, mas mantinha um `requestAnimationFrame` contínuo.
- Removidos `<canvas id="plexusCanvas">`, CSS associado, IIFE animada e chamada a `dispararPlexus()`; a reprodução ficou sem loop decorativo oculto.
- Verificação documentada: busca no HTML sem ocorrência de `plexus`; `scripts/validar-interface.sh` aprovou; harness Chromium/CDP em 412×915 registrou 60,4 FPS e 0 frames dropados durante os estados de player; console limpo.
- APK `NEXUS-VIDEO-v2.1-sem-plexus-release.apk`: `versionCode 12`, `versionName 2.1`, 1.879.861 B; SHA-256 `9b0cdf20afdeb70e8e9ff38feccbee2c4f4388464758e215e9d5abf0c6137c4a`.

## v2.2 — timeline 10 px, círculo e seek por toque/arraste (26/09/2026)

- **Sintoma/pedido:** a linha de tempo só respondia quando o dedo acertava poucos pixels. Trilho visual e fill mantidos em 10 px, com hit-area transparente de 32 px; círculo indicador 16×16 px.
- Seek em qualquer ponto e arraste por Pointer Events + `setPointerCapture`; setas esquerda/direita, Home/End e semântica ARIA slider. Lógica mantém `currentTime` sincronizado com fill/porcentagem.
- Teste HTTP/Chromium 412×915 com MP4 H.264 real de 12 s: seek por toque a ~12% (`currentTime≈1,44 s`), arraste a ~72% (`≈8,64 s`); hit-area 32 px e dimensões do DOM conferidas; `scripts/validar-interface.sh` aprovado. Harness não substitui teste WebView em aparelho.
- APK `NEXUS-VIDEO-v2.2-timeline-10px-seek-por-arraste-release.apk`: `versionCode 13`, `versionName 2.2`, 1.880.412 B; SHA-256 `62978b49a62842519d3d816c7bb982559c28f728f9b9e6b21d54fafc0fde0c97`.
- Detalhe reutilizável: [[03_Memórias/Soluções/NexusVideo-timeline-toque-e-arraste|timeline 10 px e seek por arraste]].

## v2.3 — arte e passagem suave no visor (26/09/2026)

- **Pedido do Leo:** a arte do NEXUS VIDEO deve substituir a tela cinza/preta ocasional nas trocas e suavizar a passagem entre vídeos.
- Arte original `img_21d0054bb0b6.jpg` copiada para `assets/img/visor-transicao.jpg`, byte a byte (167.805 B; SHA-256 `9d2d7bda7ba258bba92c4511b5d01c3b2226368fbce82701d63132b2d72b2e7e`). Sem resize/recompressão/ajuste tonal.
- `<img#visorPoster>` (z-index 3) cobre o vídeo ao abrir/pausar/trocar/fim/erro. CSS usa `object-fit:cover` e fade 360 ms. Só esmaece depois de `loadeddata`, `readyState>=2` e início de reprodução, alinhado ao quadro com `requestVideoFrameCallback` ou fallback rAF; callbacks/timer cancelados em nova transição.
- Teste HTTP/Chromium 412×915: arte 1280×714; permaneceu no início, saiu após quadro; reapareceu na troca de source e cobriu ao pausar; viewport sem cinza; zero exceções. `scripts/validar-interface.sh` aprovado.
- Build `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`. APK assinado v2/v3, ZIP/alinhado, pacote/Activity corretos, HTML e imagem byte idênticos às fontes.
- APK `NEXUS-VIDEO-v2.3-arte-transicao-suave-release.apk`: versionCode 14, versionName 2.3, 2.048.988 B; SHA-256 `b0f5a2285d9b2cfadffa9c400f9d091cf37d8552d17efad4e0b03f9bb3c1febc`; HTML SHA-256 `cb022ba8422fa4a0fd81a896dc24e9d19d820ff0d98b37ec58f38300a4a6442b`.
- Limite: sem aparelho; a fixture verifica UI no Chromium, não playback WebView Android físico.

## v2.4 — apoio Pix no rodapé (26/09/2026)

- Novo botão `❤️ APOIAR` centralizado no rodapé abre modal Black Cyber com chave Pix, fecha pelo X/fundo/Escape e pode copiar pelo ClipboardManager nativo ou fallback no navegador.
- A ponte JS só aceita a chave configurada (sem API genérica de clipboard). Modal móvel 412×915 e leitura posterior do clipboard foram validados em browser; chave, close target e zero exceções conferidos.
- `scripts/validar-interface.sh` e `clean assembleRelease lintRelease`: aprovados. APK Release assinado v2/v3, pacote/Activity, ZIP/alinhamento corretos, método de cópia presente no dex; nenhum aparelho para clique nativo físico.
- APK `NEXUS-VIDEO-v2.4-apoio-pix-release.apk`: versionCode 15/name 2.4, 2.050.413 B; SHA-256 `d5e5204c31296c15e0c229246203f8aa13c6aa24e1e43450bdb51453303f9113`; HTML SHA-256 `e23f58ec9280ed01f409c08a9de015384f8c48e4cae7d9a03e251ad95fa7bb11`.
- Refinamento posterior do botão: 24 px min-height, fonte 9.5 px, padding horizontal 8 px. A alteração foi incorporada ao HTML empacotado da versão 2.5 e medida na viewport 412×915.

## v2.5 — ícone neon e aprovação do app (26/09/2026)

- **Pedido:** novo ícone de launcher baseado no tema. Arte original desta versão: disco preto, aro interrompido magenta/ciano-verde, N cromado e play; sem lettering minúsculo. Cinco mipmaps RGBA (48/72/96/144/192 px). Os cinco PNGs anteriores foram preservados em `_backup/ic_launcher-original-v2.4/`.
- Gerador: `scripts/gerar-icone-video.py`. Contact sheet nos tamanhos de launcher e mdpi 48×48 inspecionados; resources dentro do APK confirmados nas cinco dimensões. O próprio Leo aprovou o resultado e disse que o app ficou “lindo sensacional”.
- Esta release contém também o refinamento de CSS do botão Pix registrado em v2.4; diferenças HTML v2.4→v2.5 limitam-se a esse botão. O fonte HTML atual é byte a byte igual ao asset no APK.
- `scripts/validar-interface.sh`: `JS valido | CSS 130/130 | 52 KB`. `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL` (42 tarefas). `scripts/verificar-apk.sh`, `zipalign -c 4`, `unzip -tq` e apksigner v2/v3 aprovados; pacote `com.leo.nexusvideo`, launcher Activity correta.
- APK `NEXUS-VIDEO-v2.5-icone-neon-release.apk`: `versionCode 16`, `versionName 2.5`, 2.032.916 B; SHA-256 `ded982050e12c9dc9e2b83681a0257f704c444f18752e716a647723f4b9fd7b5`. SHA-256 de `assets/index.html` fonte/empacotado: `deebdcc74960ecff1a93656bf6f81371a5f1b1f6d38e59bce61107837039bd82`.
- Certificado de assinatura release mantido entre versões: SHA-256 `445199a7b52c11c2b5aaeaeae556f6ceb0d596f94a8ddf984ac2972c57b5e6ce`.
- Sem `adb` disponível (`adb: command not found`): falta validar a máscara real no launcher, WebView, MediaStore e execução de MediaMuxer no aparelho. O build/empacotamento/assinatura foram validados, não o runtime Android físico.
- O código nativo `PonteApp.versao()` ainda anuncia `2.4`; não é chamado pelo HTML e permanece como dívida informativa, fora do pedido do ícone.


## v3.1 — controles do player com aro neon 2 px (27/09/2026)

- Aro dos controles de transporte alterado para 2 px, preenchimento preto sólido e gradiente do tema; mantidos dimensões e comportamento.
- Viewport HTTP/Chromium 412×915 revisada; `scripts/validar-interface.sh` aprovado; `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`.
- APK `NEXUS-VIDEO-v3.1-controles-neon-2px-release.apk`: `versionCode 22`, `versionName 3.1`, 2.050.773 B; SHA-256 `b3a0aff41108b3e9c539bc1e343146327c1d9802c45dd767dad2a66fa4ac2fb4`. Assinatura v2/v3, ZIP, zipalign, pacote e Activity aprovados; HTML empacotado idêntico à fonte (SHA-256 `f332bca209a650695241aa7dd6d9540d326911d82253b0df8e97955138105d25`).
- Validação em viewport; não foi teste físico em aparelho Android.

---

## v3.2 — fundo novo empacotado + correção do botão de fone — 27/09/2026

- `img/fundo.jpg` novo do Leo empacotado byte a byte (236.181 bytes, SHA-256 `b549199c…`); original preservado em `fundo.jpg.original-backup`.
- `PlayerService.peloFone()` restaurada após rollback do player nativo (MediaPlayer/TextureView → WebView); `BotaoFoneReceiver` voltou a compilar. Fones/Bluetooth repassam play/pause/next/prev via `nexusControle`.
- APK `NEXUS-VIDEO-v3.2-fundo-novo-release.apk`: `versionCode 23`, `versionName 3.2`, 2.279.945 B; SHA-256 `b2613f472b3360e031a391efba9ba6f548518bf9027087e84b642829216d2682`. Assinatura v2/v3, ZIP, zipalign aprovados; HTML empacotado == fonte (`e3849653…`).
- Correção documental: hash real do APK v3.1 é `4e0644484b7a26d5e29840e58bf99503e5316ef5eb8612519932756993ec870c` (2.053.139 B) — o registro antigo (`b3a0aff4…`, 2.050.773 B) estava desatualizado.

---

## v3.3 — fundo novo 2 — 27/09/2026

- `img/fundo.jpg` trocado pelo novo asset do Leo, byte a byte (hash conferido antes e depois do empacotamento). Original anterior preservado em `_backup/fundo-antes-novo-20260927.jpg`.
- APK `NEXUS-VIDEO-v3.3-fundo-novo-2-release.apk`: `versionCode 24`, `versionName 3.3`. Assinatura v2/v3, ZIP, zipalign aprovados; `fundo.jpg` e HTML empacotados == fontes.

---

## v3.4 — play no primeiro toque (foco de áudio do WebView) — 27/09/2026

**Pedido do Leo:** *"Quando vou dar player ou clico em um card toca e para rapidamente só funciona bem no segundo clique."*

- **Causa raiz (evidência: fonte do Chromium `content/browser/.../AudioFocusDelegate.java`):** o WebView Android é um foco de áudio Android — `HTMLMediaElement.play()` → `MediaSessionImpl` → `requestAudioFocus(AUDIOFOCUS_GAIN)`. O `MainActivity` pedia foco em `onResume` com `ouvinteFoco` que pausava em `AUDIOFOCUS_LOSS`; no 1º play o foco do WebView **roubava** o do app (self audio-focus steal) → `nexusFoco('perdeu')` → `player.pause()` logo após o começo. No 2º play o WebView já segura o foco (não re-pede) e tocava normal.
- **Correção:** removidos `pedirFocoAudio()` e `ouvinteFoco`. O próprio WebView suspende (`onSuspend`) em `LOSS`/`LOSS_TRANSIENT`, retoma (`onResume`) em `GAIN` e faz duck em `LOSS_TRANSIENT_CAN_DUCK` — ele já cobre ligação/WhatsApp/ligação de outro app. Lição registrada em [[03_Memórias/Soluções/nexusvideo-play-primeiro-clique]] e na skill `nexus-video` (`references/foco-de-audio-webview.md`). Vale para NEXUS MUSIC 2: nunca `requestAudioFocus` no app.
- `PonteApp.versao()` → `"3.4"`; `versionCode 25`.
- APK `NEXUS-VIDEO-v3.4-play-primeiro-toque-release.apk`: `versionName 3.4`, 2.134.986 B; SHA-256 `304db31935a4a4bf4480fce04faa644265be522a3e08167c3e11f30f4660df56`. Assinatura v2/v3, ZIP, zipalign, pacote e Activity aprovados; HTML empacotado == fonte (`e3849653…`); `fundo.jpg` empacotado == fonte (`5b42a602…`).
- Sem aparelho Android: validação por build/assinatura e pela fonte do Chromium; o play no primeiro toque confirmado por causa raiz, teste físico recomendado.

---

## v3.5 — limpeza do handoff órfão do MediaPlayer nativo (v2.9) — 27/09/2026

**Pedido do Leo:** *"Sim limpar tudo em relação a isto"* (análise anterior mostrou funções órfãs).

- Removidas do `index.html`, sem chamador nativo desde o rollback v3.2: `nxSalvarFila`, `nxCederParaNativo`, `nxSnapshotLifecycle`, `nxRestaurarPos`, `nexusFoco`, `marcarPausaUsuario`, `fontePlayerAtual`, `snapshotPlayerAtual` + chamadas mortas a `pausaExplicita/setFila/sairDaTela/cede/retomouOk/snapshotLifecycle` (inexistentes na `PonteApp`). `nxPausarTudo` usa `pararPrevia()` real.
- Mantidos: `PlayerService`, `MediaSession`, `BotaoFoneReceiver`, notificações, `avisarNativo`, `nexusControle`, `nxPausarTudo`, `nxPronto`.
- APK `NEXUS-VIDEO-v3.5-limpeza-handoff-nativo-release.apk`: `versionCode 26`, `versionName 3.5`, 2.133.388 B; SHA-256 `fd2a019ffa98250395bb5251ac1383a4c422849aa6b19e51ad41f528e77b72f4`. `validar-interface.sh` aprovado; assinatura v2/v3, ZIP, zipalign aprovados; HTML empacotado == fonte (`529b2ab7…`); `fundo.jpg` == fonte (`5b42a602…`).

---

## Estado aprovado

Leo aprovou visualmente o app/ícone após a v2.5. O estado atual está salvo em [[01_Projeto/APK/NexusVideo/NexusVideo|NexusVideo.md]] e [[01_Projeto/APK/NexusVideo/RETOMADA|RETOMADA.md]]. Não há alterações de código pendentes neste registro.
