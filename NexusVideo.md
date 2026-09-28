# NEXUS VIDEO — app Android para baixar vídeos

> **Status:** aprovado pelo Leo e congelado após a aprovação; mudanças futuras somente quando pedidas · **Projeto:** `01_Projeto/APK/NexusVideo/` · **Pacote:** `com.leo.nexusvideo` · **Autor:** Leandro
> **Versão Release:** 3.5 (`versionCode 26`) · **APK:** `NEXUS-VIDEO-v3.5-limpeza-handoff-nativo-release.apk`

O NEXUS VIDEO é um aplicativo Android nativo de tela única, com interface em WebView, para consultar uma biblioteca local e pesquisar, pré-visualizar e baixar vídeos do YouTube. A seleção da qualidade explica se o stream já tem áudio ou precisa juntar faixas. O app grava os downloads em `Movies/NexusVideo/` e reproduz vídeos usando um servidor HTTP preso ao loopback.

---

## 📊 O projeto em números

Medição dos fontes do Vault em 26/09/2026. A análise automatizada `analise-projeto-web.py` não identifica o HTML porque ele fica em `app/src/main/assets/`; as métricas abaixo foram medidas diretamente dos caminhos reais.

| Parte | Medição |
|---|---:|
| Interface principal | `app/src/main/assets/index.html` — 53.664 bytes · 1.466 linhas |
| CSS embutido | 21.990 bytes · 130 blocos `{}` balanceados · 1 `@keyframes` · sem media queries |
| JavaScript embutido | 26.788 bytes · 698 linhas · 37 funções de topo · 36 IDs DOM |
| Código Java | 5 classes · 89.476 bytes · 2.184 linhas |
| Assets de interface | 12 arquivos · 608.277 bytes (HTML, 5 fontes e 5 imagens) |
| Ícones de launcher | 5 PNGs RGBA: 48, 72, 96, 144 e 192 px |
| Scripts | 4 arquivos · 14.116 bytes |
| APK Release atual | 2.032.916 bytes; hash abaixo |
| APKs versionados na pasta | 16 Releases (1.0–2.5) |

## ✨ Funcionalidades atuais

### Biblioteca e player
- A biblioteca filtra o MediaStore para mostrar o conteúdo de `Movies/NexusVideo/`, ordenado por vídeos recentes; o player lê a mídia por HTTP Range, essencial para seek.
- Miniatura persistente cobre o estado sem quadro. A arte original `img/visor-transicao.jpg` protege a tela durante a troca e o primeiro quadro; ela só desaparece com fade quando chega um quadro de vídeo.
- O painel do topo retira o `backdrop-filter` enquanto reproduz, preservando a fluidez observada. PLEXUS/canvas decorativo foi removido, pois o loop ficava oculto atrás do player e consumia CPU.
- Timeline: trilho visual/preenchimento de **10 px**, hit-area de **32 px**, bolinha circular de **16×16 px**, toque em qualquer ponto, arraste com pointer capture e controle por teclado (setas, Home/End).

### YouTube e downloads
- NewPipeExtractor pesquisa e resolve streams; prévia usa stream progressivo com **vídeo e áudio**, encaminhado pelo proxy local que preserva Range.
- A qualidade 360p geralmente é arquivo direto. Streams adaptativos acima da faixa progressiva baixam áudio e vídeo separadamente e os juntam por `MediaExtractor` + `MediaMuxer`, sem reencode.
- A transferência usa cinco conexões paralelas, sem validação do cabeçalho de cada parte (essa validação já travou downloads em regressões anteriores).
- O download grava no MediaStore e publica progresso/etapa, cancelamento e resultado. Para MP4 seleciona áudio M4A/AAC; WebM/VP9 exige Opus quando disponível.
- Prévia, qualidades e progresso são ligados a cada resultado; não limpa os resultados irmãos.

### Apoio e identidade visual
- Botão de apoio no rodapé abre modal Pix com cópia pela ponte Android allowlisted ou pelo clipboard do navegador; sem mudar fluxo de player/download.
- O ícone atual aprovado pelo Leo usa base preta, aro neon magenta/ciano-verde e monograma N + play; preserva os cantos alpha para a máscara do launcher.
- Tema Black Cyber: fundo escuro texturizado, fontes Orbitron/Rajdhani e detalhes em rosa `#ff2f9e` e verde `#2bffa8`. Sem seleção/menu longo do navegador, exceto quando o input precisa disso.

## 🚀 Como compilar e usar

O artefato Release desta documentação já está compilado e conferido. Ao criar qualquer Release nova, incrementar `versionCode`/`versionName` primeiro.

```bash
cd "/home/leo/Documents/Obsidian Vault/01_Projeto/APK/NexusVideo"
bash scripts/validar-interface.sh
./gradlew clean assembleRelease lintRelease --no-daemon --stacktrace
bash scripts/verificar-apk.sh app/build/outputs/apk/release/app-release.apk
/home/leo/Android/Sdk/build-tools/34.0.0/zipalign -c 4 app/build/outputs/apk/release/app-release.apk
/home/leo/Android/Sdk/build-tools/34.0.0/apksigner verify -v app/build/outputs/apk/release/app-release.apk
unzip -tq app/build/outputs/apk/release/app-release.apk
```

O servidor local escuta apenas `127.0.0.1` (portas 8577–8579, primeira livre). Para uma avaliação da interface sem telefone, servir os assets por HTTP e usar viewport 412×915/CDP com rotas de API e mídia equivalentes; esse harness não comprova o MediaMuxer, o MediaStore ou a máscara do launcher num Android real.

## 🔌 API local

| Método e rota | Função |
|---|---|
| `GET /` ou `/index.html` | Interface `assets/index.html` |
| `GET /api/library` | JSON da biblioteca filtrada |
| `GET /api/youtube/search?q=` | Pesquisa, até 12 resultados |
| `POST /api/youtube/previa` | Resolve o stream de prévia com áudio e vídeo |
| `POST /api/youtube/qualidades` | Opções de resolução e recomendação |
| `POST /api/youtube` | Inicia download em background |
| `GET /api/youtube/status` | Fase, percentual, etapa, título/erro |
| `POST /api/youtube/cancel` | Solicita cancelamento |
| `GET /video/<id>` | Vídeo MediaStore com `Range`/206 |
| `GET /thumb/<id>` | Miniatura JPEG |
| `GET /api/youtube/media?u=` | Proxy da prévia, encaminha `Range` |
| `GET /api/youtube/audio?u=` | Proxy auxiliar da mídia |
| `GET /img/*`, `/fonts/*` | Imagens e fontes empacotadas |

## 📁 Estrutura relevante

```text
NexusVideo/
├── NexusVideo.md                         # esta visão geral
├── arquitetura.md                        # desenho técnico
├── RETOMADA.md                            # contexto operacional rápido
├── historico.md                           # changelog detalhado
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/index.html                 # frontend, CSS e JS
│   ├── assets/img/                       # fundos, poster e artes
│   ├── assets/fonts/                     # fontes locais
│   ├── java/com/leo/nexusvideo/           # 5 classes Java
│   └── res/mipmap-*/ic_launcher.png       # 5 densidades
├── scripts/
│   ├── validar-interface.sh
│   ├── verificar-apk.sh
│   ├── gerar-icone-video.py
│   └── gerar-icone.py                    # gerador legado
├── _backup/                              # HTML/fundo/5 ícones pré-v2.5
└── NEXUS-VIDEO-v*.apk                    # 16 artefatos Release versionados
```

## ⚙️ Manutenção e limites conhecidos

- **Ícone atual:** `python3 scripts/gerar-icone-video.py` regenera os cinco mipmaps e a prévia em `/tmp/nexus-video-icon-contact-sheet.png`. O script antigo `gerar-icone.py` não é o gerador do desenho v2.5.
- **Preservar o backup:** ícones anteriores em `_backup/ic_launcher-original-v2.4/`; não sobrescrever a cópia de segurança.
- **Chave de assinatura:** própria do NEXUS VIDEO (`nexusvideo-release.jks`, alias `nexusvideo`). Não usar a chave do NEXUS MUSIC nem documentar senhas.
- **Compatibilidade:** `minSdk 24`, `targetSdk 34`, Java 17, Android Gradle Plugin 8.1.0, Gradle Wrapper 8.2.1, NewPipeExtractor v0.26.5. O build atual gera aviso de compatibilidade AGP 8.1/compileSdk 34, mas concluiu sem erro.
- **Teste físico pendente:** na validação registrada não havia `adb` executável/dispositivo. A build, assinatura, conteúdo e imagens foram conferidos; funcionamento no WebView Android, MediaMuxer no aparelho e máscara real de launcher precisam de instalação física para comprovar.
- **Inconsistência informativa nativa:** `PonteApp.versao()` ainda retorna `"2.4"`, embora Gradle/APK sejam 2.5. A busca no HTML não encontrou chamada a essa função; não interfere na interface atual. Corrigir quando houver pedido/Release nativa futura.

## ✅ Release atual v3.2

- **Versão:** `3.2` · **versionCode:** 23 · **pacote:** `com.leo.nexusvideo` · **Activity:** `com.leo.nexusvideo.MainActivity`.
- **Artefato:** `NEXUS-VIDEO-v3.2-fundo-novo-release.apk` — 2.279.945 bytes.
- **SHA-256 APK:** `b2613f472b3360e031a391efba9ba6f548518bf9027087e84b642829216d2682`.
- **Verificado:** `clean assembleRelease lintRelease` → `BUILD SUCCESSFUL`; assinatura APK Signature Scheme v2/v3, ZIP, zipalign, pacote e Activity aprovados. `assets/index.html` idêntico à fonte (SHA-256 `e38496537cd4f772717090aeb245b8db4527af38b2f8c643c228fb7e4ccbd24b`); `img/fundo.jpg` novo empacotado byte a byte (SHA-256 `b549199ce231d05a…`).
- **Mudanças:** fundo novo do Leo + `PlayerService.peloFone()` restaurada (botões de fone).
- **Retomada:** ver [[01_Projeto/APK/NexusVideo/RETOMADA|Retomada rápida]].

## 🔗 Notas relacionadas

- [[01_Projeto/APK/NexusVideo/arquitetura|Arquitetura]] · [[01_Projeto/APK/NexusVideo/historico|Histórico]] · [[01_Projeto/APK/NexusVideo/RETOMADA|Retomada]]
- [[03_Memórias/Dicas/NexusVideo-dicas|Dicas reutilizáveis]] · [[03_Memórias/Soluções/NexusVideo-solucoes|Soluções indexadas]]
- [[03_Memórias/Soluções/NexusVideo-icone-launcher-neon|Ícone aprovado]] · [[03_Memórias/Soluções/NexusVideo-timeline-toque-e-arraste|Timeline e seek]]
- Skill Hermes: `nexus-video` (`/home/leo/.hermes/skills/web/nexus-video/SKILL.md`)

*Última atualização: 26/09/2026.*
