# NEXUS VIDEO — Retomada

> **Leia isto primeiro ao retomar o trabalho.**
> **App irmão do NEXUS MUSIC 2** — mesmo tema, mesma lógica de download do YouTube, mas
> **exclusivo para VÍDEOS**, com opinião de qualidade antes de baixar.
>
**Build corrente: v3.2 (`versionCode 23`)** — empacota o `fundo.jpg` novo do Leo (byte a byte) e corrige `PlayerService.peloFone` perdida no rollback do player; mantém controles neon 2 px e segundo plano/tela bloqueada.
> **APK:** `NEXUS-VIDEO-v3.2-fundo-novo-release.apk`
> **Documentação:** [[NexusVideo|visão geral]] · [[arquitetura|arquitetura]] · [[historico|histórico]] · [[03_Memórias/Dicas/NexusVideo-dicas|dicas]] · [[03_Memórias/Soluções/NexusVideo-solucoes|soluções]]
> **Pacote:** `com.leo.nexusvideo` · **Pasta pública:** `Movies/NexusVideo/`
> **Fonte:** `app/src/main/assets/index.html` + `app/src/main/java/com/leo/nexusvideo/`

---

## 📌 O QUE VOCÊ PRECISA SABER SOBRE O YOUTUBE

Só existe **UM** stream de vídeo que já vem com áudio no mesmo arquivo, e ele é **sempre 360p**.
Qualquer resolução acima disso vem em **dois pedaços**: um arquivo só de imagem e outro só de som.

Por isso o app faz a **junção** (muxing) depois de baixar, com `MediaExtractor` + `MediaMuxer`
(APIs do próprio Android — **sem ffmpeg, sem AAR extra**). As amostras são copiadas sem reencodar.

**Isso não é limitação do app: é assim que o YouTube entrega.** O app informa isso ao usuário na
tela de qualidades, em vez de prometer 1080p fácil.

### Formatos usados na junção
A seleção atual combina MP4/H.264 com M4A/AAC e WebM/VP9 com áudio Opus, se disponível. `YtVideoDownload.melhorAudio()` tenta manter codec e container compatíveis. Não pressupor que VP9/WebM exige reencode; este projeto já tem caminho WebM em `MediaMuxer`. Consulte [[arquitetura|arquitetura]] e valide no Android antes de alterar.

---

## 🔧 ESTRUTURA

| Arquivo | Papel |
|---|---|
| `MainActivity.java` | WebView + tela cheia do player + permissões + receber link compartilhado |
| `NexusServer.java` | HTTP local (portas 8577-8579). Serve a UI, a biblioteca, os vídeos (com Range) e a API do YouTube |
| `VideoLibrary.java` | Varre o MediaStore por vídeos, agrupa por pasta, gera miniaturas |
| `YtVideoDownload.java` | Pesquisa, lista qualidades, baixa (5 conexões) e **junta** vídeo+áudio |
| `QualidadeVideo.java` | Lógica PURA: monta as opções, as notas ("opinião") e a recomendação |

### Rotas
`GET /` · `GET /api/library` · `GET /api/youtube/search?q=` · `POST /api/youtube/qualidades`
· `POST /api/youtube` (baixar) · `GET /api/youtube/status` · `POST /api/youtube/cancel`
· `GET /video/<id>` · `GET /thumb/<id>` · `/fonts/*` · `/img/*`

---

## ⚠️ REGRAS QUE NÃO SE PODEM QUEBRAR

1. **Nunca adicionar validação de cabeçalho por parte no download paralelo.** O downloader usa
   `descobrirTamanho` (Range de 1 byte) + 5 conexões **sem** validar `Content-Range` de cada parte.
   Foi exatamente essa validação que travou o download do NEXUS MUSIC (3 regressões). O caminho bom
   está em `YtVideoDownload.baixarParaStream`.
2. **Sem loops decorativos no visor.** O `#plexusCanvas` foi **eliminado na v2.1**: ficava oculto atrás do vídeo e mantinha `requestAnimationFrame` contínuo. Não reintroduzir canvas nem loop permanente sobre o player; callback pontual/cancelável para fade é permitido.
3. **Assinatura é própria deste app** (`nexusvideo-release.jks`). Nunca usar a do NEXUS MUSIC: são applicationIds diferentes.
4. **Nada de `backdrop-filter` em ancestral do `<video>` durante playback.** O `.topo` é ancestral e o blur derrubava o player; `.tocando` desliga o filtro durante reprodução (ver `references/fps-e-fluidez.md`).

---

## 🎛️ TEMA (herdado do NEXUS MUSIC 2)

- Fontes: **Orbitron** (títulos), **Rajdhani** (texto), Café Nero (não usada aqui)
- Fundo: `assets/img/fundo.jpg` (malha diamante + espinhos cromados) em `body::before`
- Vidro: `rgba(0,0,0,.02)` + `blur(8px)`; cards `rgba(7,7,7,.34)` com aro de 1px
- Botões: aro metálico 2px + gradiente de vidro, fonte Rajdhani 11px
- Sem realce azul de toque, sem seleção de texto (é app, não página — exceto o campo de busca)
- Sem efeito de canvas: o visor mostra só vídeo/miniatura (PLEXUS eliminado na v2.1)

---

## 🛠️ COMO RETOMAR

```bash
cd "/home/leo/Documents/Obsidian Vault/01_Projeto/APK/NexusVideo"
bash scripts/validar-interface.sh          # JS/CSS (usa node)
# subir versionCode/versionName no app/build.gradle PRIMEIRO
./gradlew clean assembleRelease lintRelease --no-daemon
bash scripts/verificar-apk.sh app/build/outputs/apk/release/app-release.apk
/home/leo/Android/Sdk/build-tools/34.0.0/apksigner verify -v app/build/outputs/apk/release/app-release.apk
```
Build leva ~1 min. **Conferir depois que `assets/index.html` empacotado tem o mesmo hash da fonte.**

### Testar a interface (viewport real, sem aparelho)
Servir `app/src/main/assets` por HTTP e dirigir com CDP em 412×915. Precisa replicar as rotas do
`NexusServer` (biblioteca, vídeo com Range, busca, qualidades). Chromium precisa de
`--autoplay-policy=no-user-gesture-required`.

### Regenerar o ícone atual
```bash
python3 scripts/gerar-icone-video.py     # preto/neon, N cromado + play, cinco mipmaps
```

### Retomar documentação
- Visão geral: [[NexusVideo|NexusVideo.md]] · arquitetura: [[arquitetura]] · changelog: [[historico]]
- O app foi aprovado e está congelado. Só mexer novamente mediante pedido explícito do Leo.

---

## ✅ HISTÓRICO DE VALIDAÇÃO E RELEASE ATUAL

### Release corrente — v3.5 (`versionCode 26`) — limpeza do handoff órfão v2.9 — 27/09/2026

- Removido do `index.html` o handoff JS↔nativo do **MediaPlayer nativo (v2.9)**, órfão desde o rollback v3.2 (sem chamador nativo): `nxSalvarFila`, `nxCederParaNativo`, `nxSnapshotLifecycle`, `nxRestaurarPos`, `nexusFoco`, `marcarPausaUsuario`, `fontePlayerAtual`, `snapshotPlayerAtual` e as chamadas mortas a `pausaExplicita/setFila/sairDaTela/cede/retomouOk/snapshotLifecycle` (métodos que já não existem na `PonteApp`).
- Mantidos (ativos): `PlayerService` + `MediaSession` + `BotaoFoneReceiver` + notificações + `avisarNativo`/`nexusControle`/`nxPausarTudo`/`nxPronto`. `nxPausarTudo` agora usa `pararPrevia()` real (o `ytPararPrevia` do guard antigo não existia).
- `PonteApp.versao()` → `"3.5"`.
- `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`; `validar-interface.sh` (JS válido, CSS 130/130). Pacote/Activity, assinatura v2/v3 aprovados. 2.133.388 bytes; SHA-256 `fd2a019ffa98250395bb5251ac1383a4c422849aa6b19e51ad41f528e77b72f4`. HTML empacotado == fonte (`529b2ab7…`); `fundo.jpg` == fonte (`5b42a602…`).
- ⚠️ Atenção: segundo plano/tela bloqueada dependem só do WebView vivo (MediaPlayer nativo removido na v3.2) — teste físico segue necessário.
- Sem aparelho Android: validação por build/assinatura/harness.

### Release anterior — v3.4 (`versionCode 25`) — play no primeiro toque + fundo novo 2 — 27/09/2026

- **Correção "toca e para, só funciona no 2º clique"** (play ▶ e clique no card). Causa raiz com evidência (fonte do Chromium `AudioFocusDelegate.java`): o WebView Android é dono do foco de áudio — `HTMLMediaElement.play()` faz `requestAudioFocus(AUDIOFOCUS_GAIN)` e o `ouvinteFoco` do `MainActivity` recebia o `AUDIOFOCUS_LOSS` do próprio WebView (self audio-focus steal) e chamava `nexusFoco('perdeu')` → `player.pause()`. No 2º play o WebView já segura o foco (não re-pede) e tocava normal.
- **Correção:** `pedirFocoAudio()` + `ouvinteFoco` **removidos** do `MainActivity`. Quem suspende/retoma a mídia em perda/ganho real de foco (ligação, outro app) é o próprio WebView (`onSuspend`/`onResume`/duck no `AudioFocusDelegate`). Nunca pedir foco de áudio nem escutar perda em app WebView híbrido — detalhes em `03_Memórias/Soluções/nexusvideo-play-primeiro-clique.md` e na skill `nexus-video/references/foco-de-audio-webview.md`.
- `img/fundo.jpg` trocado pelo novo do Leo (byte a byte; SHA-256 `5b42a602…`); original em `fundo.jpg.original-backup`.
- `PonteApp.versao()` → `"3.4"`.
- `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`. Pacote/Activity, assinatura v2/v3, ZIP e zipalign aprovados. 2.134.986 bytes; SHA-256 `304db31935a4a4bf4480fce04faa644265be522a3e08167c3e11f30f4660df56`. HTML empacotado == fonte (`e3849653…`); `fundo.jpg` empacotado == fonte (`5b42a602…`).
- ⚠️ Atenção: o rollback para player de WebView removeu o MediaPlayer nativo da v2.9 — teste físico segue necessário para garantir que segundo plano/tela bloqueada continuam funcionando.
- Sem aparelho Android: validação por build/assinatura e pela fonte do Chromium.

### Release anterior — v2.6 (`versionCode 17`) — segundo plano e tela bloqueada

**Pedido do Leo:** "incrementar no nexus video rodar também em segundo plano e tela bloqueada."

- **`PlayerService.java` (novo)** — serviço em primeiro plano com `foregroundServiceType="mediaPlayback|dataSync"`. É o único jeito de isto funcionar: o vídeo toca dentro do `WebView`, e o WebView **não publica** MediaSession para o Android (o `navigator.mediaSession` do JS não sai dele). Sem sessão nativa nada aparece na tela bloqueada e o processo é o primeiro a ser morto em segundo plano.
- **MediaSession** com metadados (título, subtítulo, duração), `ACTION_SEEK_TO` e controles de play/pause/next/prev; os botões do fone e a tela bloqueada chegam por `MainActivity.chamarJs("nexusControle('...')")` — com `jsPendente`/`jsPronto` para o comando não se perder se o WebView ainda não carregou.
- **Notificação de mídia** (canal `nexus_video_player`) com os controles e a barra arrastável; **notificação de download** (canal `nexus_video_download`) com barra de progresso e botão Cancelar, lida direto do `YtVideoDownload` (o download é nativo, não depende do WebView estar vivo).
- **Primeiro plano sempre que há mídia OU download**; `precisaForeground` cobre o contrato de `startForegroundService` (notificação neutra + `stopForeground(false)` na saída, sem risco de `ForegroundServiceDidNotStartInTimeException`). Sem nada ativo, sai do primeiro plano e o serviço se libera após 15 s (o aviso "✓ Vídeo salvo" permanece).
- **Relógio adaptativo**: 1 s com o app na tela; 5 s em segundo plano (o avanço da posição vem do recurso de mídia do sistema, não de acordar a CPU a cada segundo). A posição estimada nunca anda para trás quando a interface volta a informar.
- **MainActivity**: ponte `PonteApp.tocando(titulo, sub, tocando, pos, dur)` / `parado()`; foco de áudio (`AUDIOFOCUS_LOSS` → `nexusFoco('perdeu')`); `onStart/onResume` marcam primeiro plano, `onStop` marca segundo plano; `onDestroy` pausa todo o áudio do WebView (`nxPausarTudo`) **somente** quando `isFinishing()`. Swipe nos recentes com o app em segundo plano não pausa mais o vídeo.
- **Ponte JS**: `avisarNativo()` (estado + posição, com repasse a cada 5 s durante a reprodução), `nexusControle(acao, valor)`, `nexusFoco`, `nxPausarTudo`. Minimizar não pausa nada — é exatamente o que o serviço sustenta.
- **Verificação (harness HTTP em 412×915, ponte nativa simulada):** 8/8 contratos aprovados — `tocando=true` com posição viva (10319 ms / duração 40000 ms), pausa vinda do sistema, play vindo da tela bloqueada, seek (4000 ms → 4,0 s), next/prev sem quebrar, minimizar não pausa, perda de foco pausa, `nxPausarTudo` para tudo. Zero exceções JS. O console não emitiu nada.
- **APK**: `NEXUS-VIDEO-v2.6-segundo-plano-tela-bloqueada-release.apk`; `versionCode 17`, `versionName 2.6`, 2.041.590 bytes; SHA-256 `aa520c856732ae69f352253cf603d4fec4f63872cb18d1798a5a5d9eda822067`. Assinatura v2/v3 com a chave própria. `assets/index.html` empacotado == fonte (`3804267741de9c11…`, 57.556 bytes). Manifesto no APK: `foregroundServiceType=0x3`, permissões `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC` presentes; `PlayerService`, `nexusControle`, `nxPausarTudo` e `nexusFoco` no `classes.dex`.
- **Limite:** sem aparelho Android conectado. Tela bloqueada, notificação, botões do fone e foco de áudio foram validados por compilação, pelo manifesto empacotado e pelo harness da ponte — o teste físico segue necessário.

### Release anterior — v2.5 (`versionCode 16`) — ícone NEXUS VIDEO

- Novo ícone: disco preto, aro interrompido magenta/ciano-verde, N cromado com play; sem texto pequeno. `scripts/gerar-icone-video.py` gera os mipmaps RGBA 48/72/96/144/192.
- O Leo aprovou o visual (“app lindo sensacional”); app congelado depois desta aprovação. Não fazer melhorias especulativas, alterações de código ou nova build sem pedido explícito.
- A v2.5 mantém timeline v2.2 (trilho 10px, hit-area 32px, círculo 16px, seek por toque/arraste), botão Pix menor e arte de transição.
- Os 5 ícones anteriores foram preservados em `_backup/ic_launcher-original-v2.4/`. Contact sheet e mdpi (48×48) revisados; transparente nos cantos, silhueta legível.
- `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`. APK Release `com.leo.nexusvideo`, Activity correta; assinatura v2/v3, ZIP íntegro/alinhado. APK contém PNGs 48×48, 72×72, 96×96, 144×144, 192×192.
- APK `NEXUS-VIDEO-v2.5-icone-neon-release.apk`, `versionCode 16`, `versionName 2.5`, 2.032.916 bytes; SHA-256 `ded982050e12c9dc9e2b83681a0257f704c444f18752e716a647723f4b9fd7b5`.
- Sem telefone Android conectado: ícone revisado em arquivos/preview com mask safe area, mas a máscara real do launcher deve ser conferida no aparelho.

### Release anterior — v2.4 (`versionCode 15`) — 26/09/2026

- Botão pequeno `❤️ APOIAR` centralizado no rodapé. Abre modal escuro em gradiente com mensagem para apoiar o projeto via Pix, chave fornecida pelo Leo e botão copiar.
- Copiar via ClipboardManager do Android (ponte JS limitada a aceitar somente esta chave), Clipboard API em browser seguro e fallback `execCommand` no harness. Fechar pelo X, fundo ou Escape.
- Validação Chromium/HTTP em 412×915: popup centrado e sem corte; chave completa; X ~44×44 px; toque em copiar colocou chave exata no clipboard e mostrou confirmação; Escape fechou modal; zero exceções JS. `scripts/validar-interface.sh` passou.
- Build `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`; APK Release assinado v2/v3; pacote/Activity, ZIP e alinhamento aprovados. HTML empacotado == fonte (`e23f58ec9280ed01f409c08a9de015384f8c48e4cae7d9a03e251ad95fa7bb11`); `copiarTextoPix` + chave presentes no classes.dex.
- APK `NEXUS-VIDEO-v2.4-apoio-pix-release.apk`; `versionCode 15`, `versionName 2.4`, 2.050.413 bytes; SHA-256 `d5e5204c31296c15e0c229246203f8aa13c6aa24e1e43450bdb51453303f9113`.
- Nenhum aparelho Android conectado; copiar pelo ClipboardManager nativo validado por compilação e presença da ponte, e pelo CDP/clipboard no browser, não em device físico.


### Release anterior — v2.3 (`versionCode 14`) — 26/09/2026
- Arte cobre o visor parado, pausado, fim/erro e transição entre sources. Ela começa a desaparecer com fade de 360 ms só depois de `loadeddata`/`readyState>=2` e quadro de reprodução; `requestVideoFrameCallback` preferido com fallback rAF. Callbacks/timer cancelados ao resetar a arte.
- Chromium/HTTP 412×915: arte 1280×714 carregada como object-fit cover no visor 412×232; play fica coberto no começo, arte sai depois de currentTime avançar; troca de vídeo apresenta a arte novamente; pausa mostra de volta; zero exceções JS.
- `scripts/validar-interface.sh` e `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`. APK assinado v2/v3, pacote/Activity corretos, ZIP íntegro/alinhado; HTML e imagem extraídos do APK são byte a byte os fontes.
- APK `NEXUS-VIDEO-v2.3-arte-transicao-suave-release.apk`; `versionCode 14`, `versionName 2.3`, 2.048.988 bytes; SHA-256 `b0f5a2285d9b2cfadffa9c400f9d091cf37d8552d17efad4e0b03f9bb3c1febc`. HTML SHA-256 `cb022ba8422fa4a0fd81a896dc24e9d19d820ff0d98b37ec58f38300a4a6442b`.
- Limite: nenhum aparelho/emulador Android conectado; a fixture comprova UI e transição no Chromium, não teste físico no WebView.

### Validações funcionais documentadas

- Download real: 360p progressivo 12.146.162 bytes; 720p adaptativo 24.457.601 bytes + áudio M4A 4.109.698 bytes — tamanhos conferidos.
- Junção equivalente sem reencode: H.264 986×720 + AAC 44,1 kHz, 2 canais, em MP4.
- Interface 412×915: biblioteca, vídeo avançando, busca com 10 resultados e 8 opções de qualidade; sem exceções JS.
- Testes puros documentados de `QualidadeVideo`: 17 verificações aprovadas. Não há arquivos de teste unitário no projeto atual (`testReleaseUnitTest NO-SOURCE`).

### Release anterior — v1.1 (`versionCode 2`) — 26/09/2026

- Corrigido o erro que impedia compilar: `juntar()` tornou-se método de instância para poder consultar `cancelarSolicitado`.
- `scripts/validar-interface.sh`, `testReleaseUnitTest lintRelease` e `clean assembleRelease lintRelease`: passaram (`BUILD SUCCESSFUL`). Lint emitiu 9 avisos não bloqueantes.
- APK: `NEXUS-VIDEO-v1.1-correcao-build-release.apk` — 1.881.912 bytes; SHA-256 `adec66285ace729a8a8aaa6591df3bf791227c34d8c374c3e88d726e6660d44a`.
- Pacote `com.leo.nexusvideo`, Activity `com.leo.nexusvideo.MainActivity`; assinatura Release v2/v3 aprovada e certificado igual ao da v1.0; ZIP íntegro e alinhamento aprovado.
- SHA-256 do `assets/index.html` fonte e empacotado: `fdbddc41d76f9c78c7d57a50f77ef48c82fa5a7c1d6191bcc417b9b7d75df7d2`.
- A correção não altera a lógica da junção; contudo, não havia aparelho conectado/emulador para testar MediaMuxer e reprodução nesta Release.

### Release anterior — v1.2 (`versionCode 3`) — 26/09/2026

- A prévia seleciona um stream progressivo com imagem+som (não a faixa de áudio separada), e o proxy local `/api/youtube/media` conserva suporte a Range.
- O botão ⬇ do resultado expande as qualidades dentro do card correspondente. A barra, percentual e etapa do download permanecem no mesmo card sem remover os resultados irmãos; a seleção fica bloqueada durante um download.
- Interface validada em Chromium por HTTP na viewport 412×915 com MP4 de fixture real: 640×360 decodificado, `currentTime` avançou, prévia não pausada; seletor de qualidade e barra em 22% no próprio card; conclusão a 100%; duas linhas de resultado mantidas; zero exceções JS. Esse teste usa rotas simuladas, não valida NewPipe/YouTube ao vivo.
- `scripts/validar-interface.sh` passou; `testReleaseUnitTest lintRelease` e `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`. O Lint mantém 9 avisos não bloqueantes e o projeto não contém testes unitários (`NO-SOURCE`).
- APK: `NEXUS-VIDEO-v1.2-previa-video-download-no-card-release.apk`; `com.leo.nexusvideo`, `versionCode 3`, `versionName 1.2`, Activity correta, assinatura v2/v3 e certificado igual à v1.1, ZIP íntegro e alinhado.
- SHA-256 do `assets/index.html` fonte e empacotado: `bc5a29640f470be590c4fa3c6bc7f285eabb3bc5bfdb1402e8fcc07911ea3189`.
- Tamanho 1.883.138 bytes; SHA-256 do APK `06b08f1ab29b924d95c7135e60cb9838d3fda9db40b230bf2e416804fd4c38b2`.
- Sem dispositivo/emulador para teste Android real: o fluxo NewPipe, o proxy no app e a reprodução no WebView do aparelho precisam ainda ser confirmados no WebView Android físico.

### Release anterior — v1.3 (`versionCode 4`) — 26/09/2026

- Refinamento final: não mostra mensagens de estado duplicadas fora do card quando a qualidade/progresso estão abertos; abrir detalhes oculta a costura/radius do painel anterior; parar uma busca/prévia só limpa o vídeo de prévia, não interrompe vídeo da biblioteca.
- Harness CDP repetido após esse ajuste: vídeo 640×360 decodificado e avançando; qualidade selecionada dentro do card; progresso 22% no mesmo card, demais resultados no DOM, barra global oculta e status externo vazio; conclusão 100%, botões desbloqueados, zero exceções.
- `scripts/validar-interface.sh` e `testReleaseUnitTest lintRelease`: passaram; build `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`. O projeto não contém fontes de teste unitário (`NO-SOURCE`); Lint com 9 avisos não bloqueantes.
- APK `NEXUS-VIDEO-v1.3-previa-e-progresso-no-card-release.apk`, assinado Release v2/v3, pacote e Activity corretos, ZIP/alinhamento aprovados; certificado idêntico às releases anteriores.
- HTML fonte == empacotado; SHA-256 `9397b6f934085c6f48f7d8e20c0f241cd23ee9a76bbe2d9282bfc26b54a394b1`. APK 1.883.138 bytes; SHA-256 `48d559256da9219f91d8b65edeee77fbd86283d70ff87d16e9044687967bc0cd`.
- A prévia/progresso foram validados com fixture no Chromium/CDP, mas NewPipe/YouTube real e o WebView num Android físico ainda não foram testados; nenhum dispositivo estava conectado.

### Release anterior — v1.4 (`versionCode 5`) — 26/09/2026

- **Página principal lista somente o que o app baixou:** `VideoLibrary.recarregar()` passou a filtrar o MediaStore por `RELATIVE_PATH LIKE 'Movies/NexusVideo/%'` (Android 10+) ou `DATA LIKE %Movies/NexusVideo/%` (anteriores). Os demais vídeos do celular não aparecem mais.
- **Área de download despoluída:** removidos o card de download separado, a barra global de progresso e o painel de qualidades embutido. Cada resultado é um card único com miniatura, título, canal e os botões `▶ PRÉVIA` / `⬇ BAIXAR`.
- **Pop-up de qualidades:** o botão BAIXAR abre um modal que mostra **apenas** as qualidades disponíveis (resolução + selo). Escolher uma qualidade fecha o modal e inicia o download.
- **Progresso junto ao card:** percentual, etapa e barra aparecem dentro do próprio card escolhido; os outros resultados permanecem.
- **Autoplay após baixar:** ao concluir, a interface vai para a aba VÍDEOS, recarrega a biblioteca e reproduz o vídeo recém-baixado.
- **Visor nunca vazio:** ao abrir o app, o último vídeo baixado já fica carregado no player (pausado, pronto para ▶).
- **Abas e botões de ação** em preto com texto branco e borda branca de 2px; fundo natalino original preservado (imagem movida para o `html`, pois `body::before` com `z-index:-1` ficava atrás do fundo).
- Harness CDP em 412×915, cinco estados capturados: card com os dois botões, pop-up com 2 opções (720p recomendada / 360p), progresso em 22% dentro do card com modal fechado, e autoplay com `videoWidth=640`, `paused=false`, `currentTime` avançando. Zero exceções JS. A API do YouTube foi simulada no harness.
- Build: `clean assembleRelease lintRelease` → `BUILD SUCCESSFUL`; Lint com 9 avisos não bloqueantes.
- APK `NEXUS-VIDEO-v1.4-ui-limpa-e-autoplay-release.apk`; `versionCode 5`, `versionName 1.4`, pacote/Activity corretos, assinatura v2/v3, ZIP/alinhamento aprovados.
- HTML fonte == empacotado (`1570cd11…`); `fundo.jpg` original do Leo empacotado byte a byte (`0b15c98f…`). Marcadores nativos no dex: `relative_path LIKE ?`, `_data LIKE ?`, `/NexusVideo/`.
- Tamanho 1.880.130 bytes; SHA-256 `948c91fbe2eed77bd166cd85eb1624c863196f90cd4c829c373ade68c89f1990`.
- Sem aparelho/emulador: o filtro de pasta, o fluxo NewPipe real e a reprodução no WebView Android ainda precisam de teste físico.

### Release anterior — v1.5 (`versionCode 6`) — 26/09/2026

- **Visor deixa de ficar cinza:** o `<video>` sem quadro decodificado pinta um retângulo cinza. Agora `carregarUltimoNoVisor()` define `poster` com a miniatura (`/thumb/<id>`) e, no `loadeddata`, busca `currentTime = 0.05` para o WebView decodificar e exibir o primeiro quadro real.
- As capturas o mostraram como "tela cinza do player" mesmo com o vídeo carregado (`readyState 4`, `videoWidth 640`) — a causa era a ausência de um quadro apresentado, não falta de carregamento.
- Harness CDP em 412×915: após carregar, `readyState=4`, `videoWidth=640`, `currentTime=0.05`, `paused=true`, título no visor e captura com as barras de teste coloridas (não cinza). Zero exceções JS.
- Build `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`; Lint com 9 avisos não bloqueantes.
- APK `NEXUS-VIDEO-v1.5-visor-com-quadro-release.apk`; `versionCode 6`, `versionName 1.5`, pacote/Activity corretos, assinatura v2/v3, ZIP/alinhamento aprovados.
- HTML fonte == empacotado (`4351730b…`); `fundo.jpg` segue byte a byte o original (`0b15c98f…`). Tamanho 1.880.406 bytes; SHA-256 `30fd74cd672536c6fbd19aa36034ee3831c4fca5fa962724f950c9cee8f25745`.
- Sem aparelho: o comportamento do poster e da busca de quadro no WebView Android ainda precisa de teste físico.

### Release anterior — v1.6 (`versionCode 7`) — 26/09/2026

- **"Abriu por um momento e voltou tela preta":** o WebView descarta o quadro decodificado quando não há reprodução ativa — o `poster` + `seek` da v1.5 não se sustentava. Correção: `<img class="visor-poster" id="visorPoster">` sobreposta ao `<video>` (z-index 3) com a miniatura do MediaStore (`/thumb/<id>`).
- `play` esconde a camada; `pause` e `pararPrevia()` voltam a mostrar a miniatura; `onerror` da imagem esconde sem quebrar o visor.
- Prova em harness CDP (miniaturas roxa `0x7a2bd6`): parado → `posterVisivel: true`, cor `[121,44,212]` em 69,3% da área; tocando → `posterVisivel: false`, `paused: false`, `videoWidth: 640`; pausado → miniatura de volta. Zero exceções JS.
- APK `NEXUS-VIDEO-v1.6-visor-com-miniatura-release.apk` (1.880.528 bytes; SHA-256 `7738d5ad991c3b86422dc8bb5ed86dffe44e055ab94477d3dff8e500cb6313c1`); `versionCode 7`, assinatura v2/v3, ZIP/alinhamento aprovados.

### Release anterior — v1.7 (`versionCode 8`) — 26/09/2026

- **Topo:** "NEXUS VIDEO" centralizado (`.topo-linha` com `justify-content:center`) e o contador de vidro removido — markup `#contagem`, CSS `.topo-contagem` e a linha de JS que o atualizava. O contador dentro da lista (`#contadorBiblioteca`, "1 VÍDEO") permanece.
- Medição em 412×915: `.topo-logo` centro X = 206, `.topo` centro X = 206 → **desvio 0 px**; `#contagem` e `.topo-contagem` ausentes; visor em `top 44`, largura 412.
- Build `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`; Lint sem erros bloqueantes.
- APK `NEXUS-VIDEO-v1.7-topo-central-release.apk`; `versionCode 8`, `versionName 1.7`, pacote/Activity corretos, assinatura v2/v3, ZIP/alinhamento aprovados.
- HTML fonte == empacotado (`e259c4bccccc158b121bc91f6829ebbfcc54668f7c48acbcb10b976e29736cd8`); `fundo.jpg` segue byte a byte o original (`0b15c98f…`). Tamanho 1.880.473 bytes; SHA-256 `8fedae115a22790c432f826722a27db90b56101e90fd1d7caaf50346e16f5358`.
- Sem aparelho: a centralização e a miniatura no WebView Android ainda precisam de teste físico.

### Release anterior — v1.8 (`versionCode 9`) — 26/09/2026

- **Contador de vídeos removido por completo:** saiu do topo na v1.7 e agora também a pílula `.contador` ("1 VÍDEO") abaixo das abas — markup `#contadorBiblioteca`, CSS `.contador` e a linha de JS que o atualizava. Sobrou **zero** referência a "contador" no `index.html`.
- **Box dos controles mais transparente:** `.ctl` passou de `background:rgba(16,16,16,.90)` (quase opaco) para `rgba(16,16,16,.30)` + `backdrop-filter:blur(7px)` — os botões circulares ficam vazados, só com a borda definindo o círculo.
- Medição em 412×915: `backgroundColor: rgba(16,16,16,0.30)`, `backdropFilter: blur(7px)`, borda `rgba(255,255,255,.8)`; nenhum elemento com contador no DOM; título centralizado com desvio 0 px; zero exceções JS.
- Build `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`.
- APK `NEXUS-VIDEO-v1.8-controles-transparentes-release.apk`; `versionCode 9`, `versionName 1.8`, pacote/Activity corretos, assinatura v2/v3, ZIP/alinhamento aprovados.
- HTML fonte == empacotado (`d1dd6125148c6aecf5c90d49ebcd715f409cb962a17f21b16145061b19def73b`); `fundo.jpg` byte a byte (`0b15c98f…`). Tamanho 1.880.406 bytes; SHA-256 `fbbd6617db2fe7ad4a206646c8a094068df82074acd83183606f4bf6949302e6`.

### Release anterior — v1.9 (`versionCode 10`) — 26/09/2026

**Pedido do Leo:** "Modificar apenas cor tema black cyber detalhes degrade rosa e verde."

- Paleta aplicada **somente nas cores dos detalhes** — nenhum layout, tamanho, espaçamento ou lógica alterados.
- Paleta: rosa `#ff2f9e` → verde `#2bffa8`, sempre em `linear-gradient(135deg,…)` ou `(90deg,…)`.
- **Aros em degradê** via `padding-box`/`border-box` (truque obrigatório: `border:1px solid transparent` + dois `linear-gradient`): visor, botões `.ctl`, abas, cards `.vcard`, `.yt-resultado-item`, pop-up, botão fechar, `.yt-acao` (PRÉVIA/BAIXAR).
- **Textos em degradê** (`background-clip:text` + `-webkit-text-fill-color:transparent`): `.topo-logo`, `.yt-titulo` (YOUTUBE DOWNLOAD) e `.rodape-marca`.
- **Preenchimentos em degradê**: aba ativa, `.yt-acao-previa.tocando`, `.qopc.recomendada .qopc-tag`, barras de progresso (`.trilha-fill`, `.yt-item-barra-fill`, `.yt-inline-bar-fill`).
- **Brilhos**: `.ctl.principal` com `box-shadow` rosa+verde; `.qopc.recomendada` com halo rosa.
- **Malha do visor (plexus)** interpolada: linha e ponto vão de rosa (esquerda) a verde (direita) conforme o X.
- **Estados**: `.yt-status.ok`/`.ok` → `#2bffa8`; `.erro` → `#ff4f9e`.
- Fundo: mantido byte a byte; só a sobreposição `body::after` ganhou dois radiais discretos (rosa no topo-esq, verde no canto inf-dir).
- Verificação CDP 412×915: `backgroundImage` dos aros = `linear-gradient(135deg, rgb(255,47,158), rgb(43,255,168))`; título/rodapé com `-webkit-text-fill-color: rgba(0,0,0,0)` (degradê ativo); contagem de pixels rosa/verde confirma o degradê no `.yt-titulo` (65 rosa + 133 verde). Zero exceções JS.
- Build `clean assembleRelease lintRelease`: `BUILD SUCCESSFUL`. APK `NEXUS-VIDEO-v1.9-tema-black-cyber-release.apk`; `versionCode 10`, `versionName 1.9`; assinatura v2/v3; ZIP/alinhamento aprovados. HTML empacotado == fonte (`b522f91c…`); `fundo.jpg` byte a byte (`0b15c98f…`). Tamanho 1.880.659 bytes; SHA-256 `68d8007c9cba414dabea37ce4c1c9d6637eda395654a8f448b4f83d9685d502b`.

## ⚠️ PENDÊNCIAS / LIMITES

1. **Runtime Android físico:** sem `adb` disponível para testar o APK, as permissões, o WebView, o `MediaStore` e a junção `MediaMuxer` no aparelho. Build, assinatura, ZIP/alinhamento, HTML empacotado e recursos foram verificados no host.
2. **Máscara real do launcher:** ícone e safe area foram revistos em PNG/contact sheet; aparência final sob a máscara específica depende de instalar no telefone.
3. **API mínima 24:** miniaturas usam `ContentResolver.loadThumbnail` (Android 10+); antes disso os cards podem ficar sem imagem.
4. **Versão na ponte nativa:** `MainActivity.PonteApp.versao()` ainda retorna `2.4`; busca no HTML não encontrou consumidor. Corrigir junto a uma futura alteração nativa/Release, não por conta própria.
