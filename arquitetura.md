# NEXUS VIDEO — Arquitetura e análise técnica

> Visão geral/uso: [[01_Projeto/APK/NexusVideo/NexusVideo|NEXUS VIDEO]]. Histórico: [[01_Projeto/APK/NexusVideo/historico|histórico]]. Retomada: [[01_Projeto/APK/NexusVideo/RETOMADA|RETOMADA]].
>
> Medição de fontes no Vault em 26/09/2026. Projeto: `01_Projeto/APK/NexusVideo/`.

## 1. Visão geral

```text
┌──────────────── Android / MainActivity ─────────────────┐
│   WebView (JS/DOMStorage)  ←→  Ponte AndroidApp          │
│         │ carrega http://127.0.0.1:8577-8579/            │
│         ▼                                                │
│ NexusServer (socket loopback + pool 6 clientes)           │
│   ├── VideoLibrary ↔ ContentResolver / MediaStore         │
│   └── YtVideoDownload ↔ NewPipeExtractor / internet        │
│          ├─ pesquisa/stream → YouTube                     │
│          ├─ download por 5 conexões + arquivos temporários │
│          └─ MediaExtractor + MediaMuxer → MediaStore      │
└───────────────────────────────────────────────────────────┘
```

O app combina uma interface local empacotada com uma API HTTP que só escuta em `127.0.0.1`. A UI não precisa de servidor externo: o backend nativo serve HTML, fontes, imagens, biblioteca e operações do extrator.

## 2. Backend Android

### 2.1 Constantes e configuração observadas

- App/package/namespace: `com.leo.nexusvideo`; Activity de launcher e compartilhamento: `.MainActivity`.
- `compileSdk 34`, `targetSdk 34`, `minSdk 24`, Java 17.
- Gradle 8.2.1 / Android Gradle Plugin 8.1.0; biblioteca externa: NewPipeExtractor `v0.26.5` via JitPack.
- Servidor local tenta portas `{8577, 8578, 8579}` e faz bind em `127.0.0.1`; pool fixo de seis clientes.
- Pasta de saída: `Movies/NexusVideo/`. Android Q+: grava como item pendente no MediaStore e libera com `IS_PENDING=0` ao finalizar; API antiga usa a pasta pública Movies.

### 2.2 Classes e responsabilidades

| Classe | Medição | Responsabilidade |
|---|---:|---|
| `MainActivity.java` | 299 linhas · 11.107 B | Inicializa biblioteca, downloader, servidor e WebView; fullscreen do player, permissões, ciclo de vida, intents compartilhados e ponte JS |
| `NexusServer.java` | 494 linhas · 19.689 B | Socket HTTP loopback, 3 portas alternativas, pool de conexões, API e Range para reprodução |
| `VideoLibrary.java` | 296 linhas · 11.408 B | Consulta/filtra MediaStore, gera JSON e miniatura, abre stream por URI |
| `YtVideoDownload.java` | 916 linhas · 40.231 B | NewPipe, cache de busca/prévia, streams/qualidades, transferência paralela, progresso/cancelamento, junção e gravação |
| `QualidadeVideo.java` | 179 linhas · 7.041 B | Regras puras de opções, ordenação, tipo/nota e recomendação |

### 2.3 Rotas HTTP (servidas por `NexusServer.tratar`)

| Verbo | Rota | Dados/efeito |
|---|---|---|
| GET | `/`, `/index.html` | Lê e devolve `assets/index.html` |
| GET | `/api/library` | `VideoLibrary.comoJson()` |
| GET | `/api/youtube/search?q=...` | Busca NewPipe; até 12 vídeos; cache curto |
| POST | `/api/youtube/previa` | JSON `{url}` → URL do vídeo progressivo com áudio |
| POST | `/api/youtube/qualidades` | JSON `{url}` → catálogo e melhor opção |
| POST | `/api/youtube` | JSON `{url, qualidade}` → aceita e inicia thread de download |
| GET | `/api/youtube/status` | JSON ativo/percentual/fase/etapa/título/erro/mensagem |
| POST | `/api/youtube/cancel` | Solicita cancelamento cooperativo |
| GET | `/video/<id>` | Stream MediaStore com resposta parcial 206 e Content-Range quando pede Range |
| GET | `/thumb/<id>` | Miniatura JPEG do MediaStore (Android Q+) |
| GET | `/api/youtube/media?u=<url>` | Proxy de stream progressivo; repassa o Range do player |
| GET | `/api/youtube/audio?u=<url>` | Proxy auxiliar de mídia; repassa Range |
| GET | `/img/<arquivo>`, `/fonts/<arquivo>` | Assets empacotados; rejeita caminho contendo `..` |

O `NexusServer` é HTTP mínimo, não um framework. Novas rotas precisam respeitar a distinção de método e continuar restritas ao loopback; não expor `ServerSocket` à LAN sem pedido explícito.

### 2.4 Fluxo de qualidade e download

```text
UI → POST qualidades(url)
   → NewPipe busca progressivos + adaptativos
   → QualidadeVideo.montar(): uma opção por altura, direta vence o empate
   → UI mostra nota e recomendação
   → usuário escolhe → POST /api/youtube
   → thread resolve stream/áudio compatíveis
   → baixa (5 conexões) → se adaptativo, junta amostras em ordem de timestamp
   → MediaStore pendente → finaliza/indexa → recarrega biblioteca → status concluído
```

- Stream direto traz vídeo+áudio no mesmo arquivo; a documentação do app observa o caso usual de 360p.
- Adaptativos trazem imagem sem áudio e exigem faixa sonora. A opção MP4 procura M4A/AAC; WebM/VP9 procura Opus.
- `baixarParaStream()` usa `Range: bytes=0-0` para descobrir tamanho, segmenta em cinco intervalos e agrega partes em ordem. **Não adicionar validação de `Content-Range` por parte:** esse código é sensível a diferenças do endpoint e já foi causa de travamento em outro projeto.
- Ao juntar, `MediaExtractor` lê ambas as pistas; `MediaMuxer` recebe a amostra com menor timestamp, normalizada pelo instante inicial. Isso evita arquivo com todas as amostras de vídeo antes das de áudio.
- Percentual e fase são publicados em estado volátil para polling; a junção usa 99% até finalizar porque não possui progresso granular confiável.
- Cancelamento é cooperativo nos loops de download e de mux. Limpar temporários e remover item MediaStore pendente no erro é parte do contrato.

### 2.5 Segurança e limites

- Servidor de UI liga somente ao loopback. O manifest declara INTERNET, permissões de leitura de vídeo, notificações e cleartext para o HTTP local.
- `MainActivity` habilita JavaScript/DOM Storage no WebView e expõe `AndroidApp`. A ponte de copiar texto aceita somente igualdade exata à chave Pix já configurada; não é clipboard genérico.
- `MainActivity.PonteApp.versao()` ainda retorna `2.4`, enquanto Gradle e APK já são 2.5. Não há chamada a essa função no HTML atual; registrar como inconsistência informativa, sem alterar a versão entregue.
- `minSdk 24`; miniaturas nativas usam `ContentResolver.loadThumbnail` e podem faltar antes do Android 10.
- Não havia `adb` disponível em 26/09/2026 (`adb: command not found`). Não afirmar teste físico de WebView, MediaStore ou `MediaMuxer` da v2.5.

## 3. Frontend WebView

### 3.1 Estrutura medida

`app/src/main/assets/index.html`: 53.664 bytes, 1.466 linhas. CSS 21.990 bytes, 130 pares de chaves balanceados, uma animação `apoioEntrar`, sem `@media`; JavaScript inline 26.788 bytes/698 linhas, 37 funções de topo e 36 IDs DOM. Tem exatamente um bloco `<script>` e um `<style>` inline.

### 3.2 Estado e principais funções

| Área | Estado/handlers principais | Efeito |
|---|---|---|
| Biblioteca | `lib`, `fila`, `indiceFila`, `videoSelecionado`; `carregarBiblioteca`, `renderGrade`, `tocarDaLista`, `tocarIndice` | Carrega listagem local, cards, sequência e playback |
| Arte de transição | `posterVisorToken`, `apresentacaoNovoQuadro`, callbacks de quadro/RAF | Deixa poster visível durante a troca e remove apenas depois do quadro real |
| Timeline | `trilha`, `ponteiroTimeline`, `posicionarTimeline`, Pointer Events e keydown | Seek via pointerdown/move/up/cancel/capture perdido, teclado e ARIA slider |
| Pesquisa | `buscaResultados`, `pesquisar`, `renderResultados`, `tocarPrevia` | Busca, cards e prévia audiovisual no player principal |
| Qualidades | `tokenQualidades`, `urlAtual`, `opcoesAtuais`, `abrirQualidades`, `renderQualidades` | Modal sincronizado à resposta async e à opção recomendada |
| Download | `downloadEmAndamento`, `baixandoAtual`, `acompanhar`, `atualizarProgressoNoCard` | Poll de status, progresso e estado do resultado; bloqueia ações concorrentes |
| Pix | `CHAVE_PIX`, `abrirApoio`, `fecharApoio`, clipboard | Modal com fecho pelo botão/fundo/Escape e cópia segura |

### 3.3 Efeitos colaterais/ordem visual crítica

- `<video>` tem z-index 2; poster sobreposto tem z-index 3. `play`/quadro real coordena esmaecimento. Evitar voltar a depender de `<video poster>`/seek como frame persistente: WebView pode descartar frame quando pausa.
- `.topo` é ancestral do vídeo; enquanto o player toca `.topo.tocando` remove ambos `backdrop-filter` para evitar composição cara a cada quadro.
- O canvas PLEXUS não existe mais. Há `requestAnimationFrame` somente como fallback pontual para agendar o fade de transição, não um loop decorativo permanente.
- Timeline: trilho/hit area `.trilha` é 32px; pseudo-elemento de trilho e `.trilha-fill` são 10px; `.trilha-bolinha` é 16px. Seek calcula `(clientX - rect.left)/rect.width`, limita em 0–1, atualiza `currentTime`, fill e `aria-valuenow`.
- Pointer capture mantém o arraste vivo quando o dedo sai do retângulo; preservar `touch-action:none`, `preventDefault` e limpeza nos eventos up/cancel/lostpointercapture.
- Download e renderização usam `textContent`/escape para títulos e nomes vindos da rede. Preservar tokens para ignorar respostas assíncronas antigas.

## 4. CSS e identidade visual

- Tema Black Cyber: `#ff2f9e` ↔ `#2bffa8` em aro/texto/progresso, fundo com assets originais e radiais discretos; Orbitron nos títulos, Rajdhani no corpo.
- `.visor` é 16:9 e preto, vídeo `object-fit:contain`; poster decorativo/estado idle usa `object-fit:cover` e transição de opacidade 360 ms.
- `@keyframes apoioEntrar` é a animação CSS única contada na folha atual. Não adicionar loops/canvas ao player sem pedido e medição.
- CSS balanceado conferido por `scripts/validar-interface.sh`; o script mede JS/CSS de forma estrutural, não substitui viewport rasterizada.

## 5. Decisões técnicas

| Decisão | Razão/limite |
|---|---|
| WebView em servidor HTTP loopback | URLs absolutas `/img`, `/fonts`, `/api` funcionam com origem estável e API nativa local |
| `MediaStore` como destino | Arquivos aparecem na biblioteca pública sem espalhar projeto em diretórios privados |
| `MediaExtractor`/`MediaMuxer`, sem ffmpeg/AAR | Junta sem reencode usando APIs Android e mantém APK pequeno |
| MP4+A​AC / WebM+Opus por codec | Extensão/container correto e compatibilidade real do player |
| 5 conexões sem validar cada Range | Reutiliza downloader testado; evita o travamento já observado ao validar Content-Range |
| Poster em `<img>` separado | O frame congelado do WebView pode desaparecer ao pausar; imagem sobreposta é estável |
| Hit area 32px para trilho de 10px | Aumenta tolerância do toque sem engrossar visualmente a timeline |
| SVG/canvas decorativo não sobreposto ao vídeo | Evita trabalho contínuo e custo do compositor no playback |

## 6. Release atual e verificação

- `versionName 2.5`, `versionCode 16`; APK Release `NEXUS-VIDEO-v2.5-icone-neon-release.apk`.
- `clean assembleRelease lintRelease` → `BUILD SUCCESSFUL` (42 tarefas); warning AGP 8.1/compileSdk 34 e SDK XML v4 não bloqueou.
- `scripts/verificar-apk.sh`, assinatura apksigner v2/v3, `zipalign -c 4`, `unzip -tq` aprovados; package e launcher Activity conferidos.
- APK: 2.032.916 B; SHA-256 `ded982050e12c9dc9e2b83681a0257f704c444f18752e716a647723f4b9fd7b5`.
- `assets/index.html` do APK == fonte; SHA-256 `deebdcc74960ecff1a93656bf6f81371a5f1b1f6d38e59bce61107837039bd82`.
- Recursos de launcher no APK: 48×48, 72×72, 96×96, 144×144, 192×192 RGBA. Gerador salva os nomes estáveis `mipmap-*/ic_launcher.png` e é `scripts/gerar-icone-video.py`.
- O certificado Release segue estável entre versões; SHA-256 do certificado `445199a7b52c11c2b5aaeaeae556f6ceb0d596f94a8ddf984ac2972c57b5e6ce`.
- Ícone visualmente revisado pelo Leo e aprovado. Sem instalação física disponível para validar a máscara específica do launcher.

## 7. Dívidas técnicas

| Item | Estado |
|---|---|
| `PonteApp.versao()` anuncia 2.4 | Desatualizado, sem consumidor no HTML atual; corrigir na próxima mudança nativa/Release, não regenerar APK sem pedido |
| Android real | Sem `adb`; WebView, permissões, MediaStore, playback e mux final não exercitados neste host |
| Masking launcher | Arquivos pequenos e safe area revisados, mas máscara do launcher real depende do dispositivo |
| Aviso de toolchain | AGP 8.1.0 não declara compileSdk 34 testado; não impediu o build atual |
| Código morto futuro | Fazer auditoria separada somente se solicitada; não remover em refinamento estético |

## Referências

- UI e requisitos de retomada: [[01_Projeto/APK/NexusVideo/NexusVideo|NexusVideo.md]] · [[01_Projeto/APK/NexusVideo/RETOMADA|RETOMADA.md]]
- Changelog: [[01_Projeto/APK/NexusVideo/historico|historico.md]]
- Skills: `nexus-video`, `android-build`
- Soluções específicas: [[03_Memórias/Soluções/NexusVideo-timeline-toque-e-arraste|timeline]], [[03_Memórias/Soluções/NexusVideo-player-60fps-blur-ancestral|FPS]], [[03_Memórias/Soluções/NexusVideo-icone-launcher-neon|ícone]]

*Última atualização: 26/09/2026.*
