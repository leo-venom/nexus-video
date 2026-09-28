package com.leo.nexusvideo;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Download de VÍDEOS do YouTube direto no aparelho.
 *
 * <p>Reaproveita o caminho de download já comprovado no NEXUS MUSIC (tamanho por
 * `Range: bytes=0-0` + 5 conexões paralelas, SEM validar cabeçalho de cada parte
 * — foi justamente essa validação que travou o download do outro app).</p>
 *
 * <p><b>O que este app tem de diferente:</b> o YouTube só oferece UM stream de
 * vídeo com áudio embutido (360p). Acima disso o vídeo e o áudio vêm separados,
 * então aqui os dois são baixados e JUNTADOS com MediaMuxer/MediaExtractor, que
 * são APIs do próprio Android — não é preciso empacotar ffmpeg.</p>
 */
public class YtVideoDownload {

    private static final String TAG = "NexusVideoYt";
    private static final long CACHE_BUSCA_MS = 60_000L;

    private static boolean newPipePronto = false;

    private final Context ctx;
    private final VideoLibrary library;

    // ---------------------------------------------------------------- estado
    private volatile boolean ativo;
    private volatile boolean cancelarSolicitado;
    private volatile double percentual;
    private volatile String fase = "";
    private volatile String etapa = "";
    private volatile String titulo = "";
    private volatile String erro = "";
    private volatile String mensagem = "";
    private volatile String qualidade = "";

    private static final class Cache {
        final long criado;
        final String json;
        Cache(long criado, String json) { this.criado = criado; this.json = json; }
    }

    private final Map<String, Cache> cacheBusca = new java.util.HashMap<>();
    private final Map<String, Cache> cachePrevia = new java.util.HashMap<>();
    private final Object travarCache = new Object();

    public YtVideoDownload(Context ctx, VideoLibrary library) {
        this.ctx = ctx;
        this.library = library;
    }

    // ------------------------------------------------------------------ //
    //  NewPipe
    // ------------------------------------------------------------------ //

    public static synchronized void preparar() {
        if (newPipePronto) return;
        NewPipe.init(new NexusDownloader());
        newPipePronto = true;
        Log.i(TAG, "NewPipe Extractor pronto");
    }

    /**
     * Downloader mínimo do NewPipe usando HttpURLConnection (evita trazer OkHttp).
     *
     * <p><b>Anti-bloqueio do YouTube (v3.6):</b> o YouTube bloqueia por "comportamento
     * robótico" quando o fingerprint é de cliente não-navegador. Este downloader agora
     * imita um Chrome Android real em TODAS as requests, envia cookies que anulam o
     * consentimento/SOCS (reduz flag de bot), respeita um intervalo mínimo entre
     * requests e faz retry com backoff exponencial em HTTP 429/ReCaptcha.</p>
     */
    static class NexusDownloader extends Downloader {

        /** User-Agent estável de Chrome Android — nunca variar entre chamadas. */
        private static final String UA =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

        /**
         * Cookies que o YouTube usa para identificar sessão sem banner e sem flag de
         * bot novo. `SOCS=CAI` marca consentimento já resolvido; `CONSENT=YES+1`
         * é o equivalente antigo (mantido por segurança).
         */
        private static final String COOKIES =
                "SOCS=CAI; CONSENT=YES+1; GPS=1; YSC=1";

        /** Intervalo mínimo entre requests consecutivas (ms). Abaixo disso o YouTube
         *  acusa "robotic behavior" mesmo com fingerprint correto. */
        private static final long INTERVALO_MIN_MS = 450L;

        /** Máximo de tentativas por request em 429/ReCaptcha. */
        private static final int MAX_TENTATIVAS = 3;

        /** Momento do fim da última request concluída (para espaçar). */
        private static long ultimaReqFimMs = 0L;

        /** Último erro 429 (para backoff global se repetir). */
        private static long ultimo429Ms = 0L;

        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            IOException ultimaFalha = null;
            for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
                esperarVez();
                try {
                    Response r = executarUmaVez(request);
                    int code = r.responseCode();
                    if (code == 429 || code == 503) {
                        // Rate limit: backoff exponencial (1s, 2s, 4s) e nova tentativa
                        long espera = (1L << (tentativa - 1)) * 1000L;
                        ultimo429Ms = System.currentTimeMillis();
                        Log.w(TAG, "HTTP " + code + " (rate limit) — aguardando " + espera + "ms");
                        if (tentativa == MAX_TENTATIVAS)
                            throw new IOException("O YouTube limitou o IP — aguarde e tente de novo");
                        SystemClock.sleep(espera);
                        continue;
                    }
                    ultimaReqFimMs = System.currentTimeMillis();
                    return r;
                } catch (IOException io) {
                    ultimaFalha = io;
                    String msg = String.valueOf(io.getMessage());
                    // ReCaptcha do NewPipe: também é sinal de bloqueio por fingerprint
                    if (msg.contains("reCaptcha") || msg.contains("Recaptcha")) {
                        long espera = (1L << (tentativa - 1)) * 1500L;
                        Log.w(TAG, "ReCaptcha — aguardando " + espera + "ms (tentativa " + tentativa + ")");
                        if (tentativa == MAX_TENTATIVAS) throw io;
                        SystemClock.sleep(espera);
                        continue;
                    }
                    throw io;
                }
            }
            throw ultimaFalha != null ? ultimaFalha : new IOException("Falha ao acessar o YouTube");
        }

        /** Executa UMA request (sem retry). Fingerprint completo de Chrome Android. */
        private Response executarUmaVez(Request request) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
            c.setRequestMethod(request.httpMethod());
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);

            // 1) Fingerprint de navegador — ANTES do loop para ser sempre o primeiro
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8");
            c.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,"
                    + "image/avif,image/webp,*/*;q=0.8");
            c.setRequestProperty("Cookie", COOKIES);

            // 2) Headers vindos do NewPipe (sobrescrevem os defaults se vierem)
            for (Map.Entry<String, List<String>> e : request.headers().entrySet())
                for (String v : e.getValue()) c.addRequestProperty(e.getKey(), v);

            byte[] dados = request.dataToSend();
            if (dados != null && dados.length > 0) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type",
                        c.getRequestProperty("Content-Type") != null
                                ? c.getRequestProperty("Content-Type")
                                : "application/json");
                try (OutputStream os = c.getOutputStream()) { os.write(dados); }
            }

            int codigo = c.getResponseCode();
            InputStream in = codigo >= 400 ? c.getErrorStream() : c.getInputStream();
            String corpo = in == null ? "" : lerTexto(in);
            return new Response(codigo, c.getResponseMessage(), c.getHeaderFields(), corpo,
                    c.getURL().toString());
        }

        /**
         * Espaçamento entre requests. Se cair um 429 recentemente, dobra a janela
         * de resfriamento — o YouTube pune re-tentativas imediatas.
         */
        private static synchronized void esperarVez() {
            long agora = System.currentTimeMillis();
            long intervalo = INTERVALO_MIN_MS;
            if (ultimo429Ms > 0 && agora - ultimo429Ms < 60_000L) intervalo *= 3;
            long decorrido = agora - ultimaReqFimMs;
            if (decorrido < intervalo) SystemClock.sleep(intervalo - decorrido);
            ultimaReqFimMs = System.currentTimeMillis();
        }

        private static String lerTexto(InputStream is) throws IOException {
            try (InputStream entrada = is) {
                ByteArrayOutputStream saida = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = entrada.read(buffer)) > 0) saida.write(buffer, 0, n);
                return saida.toString("UTF-8");
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Estado
    // ------------------------------------------------------------------ //

    public boolean ativo() { return ativo; }

    public synchronized String cancelar() {
        if (!ativo) return "{\"ok\":false,\"erro\":\"Nenhum download ativo\"}";
        cancelarSolicitado = true;
        fase = "cancelando";
        etapa = "cancelando download";
        return "{\"ok\":true}";
    }

    // ------------------------------------------------------------------ //
    //  Leituras usadas pela notificação e pelo serviço (segundo plano)
    // ------------------------------------------------------------------ //

    public String tituloAtual() { return titulo; }

    public String etapaAtual() { return etapa; }

    public String faseAtual() { return fase; }

    public String erroAtual() { return erro; }

    public String qualidadeAtual() { return qualidade; }

    public double percentualAtual() { return percentual; }

    public String statusJson() {
        StringBuilder json = new StringBuilder(220);
        json.append("{\"ativo\":").append(ativo)
                .append(",\"percentual\":").append(String.format(Locale.US, "%.1f", percentual))
                .append(",\"fase\":\"").append(NexusServer.escapar(fase)).append("\"")
                .append(",\"etapa\":\"").append(NexusServer.escapar(etapa)).append("\"")
                .append(",\"titulo\":\"").append(NexusServer.escapar(titulo)).append("\"")
                .append(",\"qualidade\":\"").append(NexusServer.escapar(qualidade)).append("\"")
                .append(",\"erro\":\"").append(NexusServer.escapar(erro)).append("\"")
                .append(",\"mensagem\":\"").append(NexusServer.escapar(mensagem)).append("\"}");
        return json.toString();
    }

    // ------------------------------------------------------------------ //
    //  Pesquisa
    // ------------------------------------------------------------------ //

    public String pesquisar(String termo) {
        if (termo == null || termo.trim().isEmpty())
            return "{\"ok\":false,\"erro\":\"Digite o nome do vídeo\",\"resultados\":[]}";

        String busca = termo.trim();
        if (busca.length() > 120) busca = busca.substring(0, 120);
        String chave = busca.toLowerCase(Locale.ROOT);

        synchronized (travarCache) {
            Cache c = cacheBusca.get(chave);
            if (c != null && System.currentTimeMillis() - c.criado < CACHE_BUSCA_MS) return c.json;
        }

        try {
            preparar();
            SearchExtractor ex = ServiceList.YouTube.getSearchExtractor(busca);
            ex.fetchPage();
            List<InfoItem> itens = ex.getInitialPage().getItems();
            StringBuilder json = new StringBuilder("{\"ok\":true,\"resultados\":[");
            int n = 0;
            for (InfoItem item : itens) {
                if (!(item instanceof StreamInfoItem)) continue;
                if (n >= 12) break;
                StreamInfoItem v = (StreamInfoItem) item;
                if (n > 0) json.append(',');
                String capa = "";
                List<org.schabi.newpipe.extractor.Image> imgs = v.getThumbnails();
                if (imgs != null && !imgs.isEmpty()) capa = imgs.get(0).getUrl();
                String canal = v.getUploaderName();
                if (canal == null || canal.isEmpty()) canal = "YouTube";
                json.append("{\"titulo\":\"").append(NexusServer.escapar(v.getName()))
                        .append("\",\"canal\":\"").append(NexusServer.escapar(canal))
                        .append("\",\"duracao\":\"").append(formatarDuracao(v.getDuration()))
                        .append("\",\"url\":\"").append(NexusServer.escapar(v.getUrl()))
                        .append("\",\"capa\":\"").append(NexusServer.escapar(capa))
                        .append("\"}");
                n++;
            }
            json.append("]}");
            String resposta = json.toString();
            synchronized (travarCache) {
                cacheBusca.put(chave, new Cache(System.currentTimeMillis(), resposta));
                if (cacheBusca.size() > 12)
                    cacheBusca.remove(cacheBusca.keySet().iterator().next());
            }
            return resposta;
        } catch (Exception e) {
            Log.w(TAG, "falha na pesquisa: " + e.getMessage());
            return "{\"ok\":false,\"erro\":\"Não foi possível pesquisar agora\",\"resultados\":[]}";
        }
    }

    // ------------------------------------------------------------------ //
    //  Qualidades disponíveis ("opinião" de cada uma)
    // ------------------------------------------------------------------ //

    /**
     * Lista o que dá para baixar neste vídeo, com a opinião de cada opção.
     * Devolve também qual o app recomenda.
     */
    public String qualidades(String url) {
        if (url == null || url.trim().isEmpty())
            return "{\"ok\":false,\"erro\":\"Vídeo inválido\"}";
        try {
            preparar();
            StreamExtractor ex = ServiceList.YouTube.getStreamExtractor(url.trim());
            ex.fetchPage();
            List<VideoStream> progressivos = ex.getVideoStreams();
            List<VideoStream> adaptativos = ex.getVideoOnlyStreams();

            List<QualidadeVideo.Opcao> opcoes = QualidadeVideo.montar(progressivos, adaptativos);
            QualidadeVideo.Opcao melhor = QualidadeVideo.melhor(opcoes);

            StringBuilder json = new StringBuilder(1024);
            json.append("{\"ok\":true,\"titulo\":\"").append(NexusServer.escapar(ex.getName()))
                    .append("\",\"canal\":\"").append(NexusServer.escapar(canalDe(ex)))
                    .append("\",\"duracao\":\"").append(formatarDuracao(ex.getLength()))
                    .append("\",\"opcoes\":[");
            for (int i = 0; i < opcoes.size(); i++) {
                QualidadeVideo.Opcao o = opcoes.get(i);
                if (i > 0) json.append(',');
                json.append("{\"rotulo\":\"").append(NexusServer.escapar(o.rotulo))
                        .append("\",\"tipo\":\"").append(o.tipoJson())
                        .append("\",\"nota\":\"").append(NexusServer.escapar(o.nota))
                        .append("\",\"mp4\":").append(o.mp4)
                        .append(",\"recomendada\":").append(o == melhor)
                        .append('}');
            }
            json.append("],\"recomendada\":\"")
                    .append(melhor == null ? "" : NexusServer.escapar(melhor.rotulo))
                    .append("\"}");
            return json.toString();
        } catch (Exception e) {
            Log.w(TAG, "qualidades: " + e.getMessage());
            return "{\"ok\":false,\"erro\":\"Não foi possível ler as qualidades deste vídeo\"}";
        }
    }

    private static String canalDe(StreamExtractor ex) {
        String canal = null;
        try {
            canal = ex.getUploaderName();
        } catch (Exception ignored) {
            // alguns vídeos não expõem o canal; cai no padrão
        }
        return (canal == null || canal.isEmpty()) ? "YouTube" : canal;
    }

    // ------------------------------------------------------------------ //
    //  Prévia (tocar no card do resultado)
    // ------------------------------------------------------------------ //

    /**
     * URL de áudio do vídeo, para a PRÉVIA tocar no player principal.
     *
     * <p>Não usa o arquivo progressivo de 360p (que tem vídeo junto e é pesado
     * — ~12 MB por minuto): usa só a faixa de ÁUDIO mais leve, que dá a mesma
     * amostra da música com uma fração dos bytes. Caindo para o progressivo se
     * o vídeo não oferecer faixa de áudio separada.</p>
     */
    public String previa(String url) {
        if (url == null || url.trim().isEmpty())
            return "{\"ok\":false,\"erro\":\"Resultado inválido\"}";

        String chave = url.trim();
        synchronized (travarCache) {
            Cache c = cachePrevia.get(chave);
            if (c != null && System.currentTimeMillis() - c.criado < 300_000L) return c.json;
        }

        try {
            preparar();
            StreamExtractor ex = ServiceList.YouTube.getStreamExtractor(chave);
            ex.fetchPage();

            /* A prévia precisa de imagem E som no mesmo stream progressivo.
               Os adaptativos são só vídeo; a lista progressiva do YouTube inclui
               as faixas com áudio, normalmente até 360p. */
            List<VideoStream> progressivos = ex.getVideoStreams();
            VideoStream escolhido = null;
            if (progressivos == null) progressivos = java.util.Collections.emptyList();
            for (VideoStream stream : progressivos) {
                if (stream == null || stream.isVideoOnly()) continue;
                if (escolhido == null) {
                    escolhido = stream;
                    continue;
                }
                int alturaStream = QualidadeVideo.alturaDe(stream.getResolution());
                int alturaEscolhida = QualidadeVideo.alturaDe(escolhido.getResolution());
                if (alturaStream > alturaEscolhida
                        || (alturaStream == alturaEscolhida
                        && !QualidadeVideo.ehMp4(escolhido)
                        && QualidadeVideo.ehMp4(stream))) {
                    escolhido = stream;
                }
            }
            if (escolhido == null)
                return "{\"ok\":false,\"erro\":\"Prévia de vídeo indisponível para este resultado\"}";
            String alvo = escolhido.getUrl();
            if (alvo == null || alvo.trim().isEmpty())
                return "{\"ok\":false,\"erro\":\"Prévia de vídeo indisponível para este resultado\"}";

            String resposta = "{\"ok\":true,\"url\":\"" + NexusServer.escapar(alvo) + "\"}";
            synchronized (travarCache) {
                cachePrevia.put(chave, new Cache(System.currentTimeMillis(), resposta));
                if (cachePrevia.size() > 24)
                    cachePrevia.remove(cachePrevia.keySet().iterator().next());
            }
            return resposta;
        } catch (Exception e) {
            Log.w(TAG, "prévia: " + e.getMessage());
            return "{\"ok\":false,\"erro\":\"Não foi possível carregar a prévia de vídeo\"}";
        }
    }

    // ------------------------------------------------------------------ //
    //  Download
    // ------------------------------------------------------------------ //

    public synchronized String iniciar(String url, String rotuloQualidade) {
        if (url == null || url.trim().isEmpty())
            return "{\"ok\":false,\"erro\":\"Informe o link do YouTube\"}";
        String limpa = url.trim();
        if (!limpa.contains("youtube.com") && !limpa.contains("youtu.be"))
            return "{\"ok\":false,\"erro\":\"Só links do YouTube\"}";
        if (ativo)
            return "{\"ok\":false,\"erro\":\"Já existe um download em andamento\"}";

        ativo = true;
        cancelarSolicitado = false;
        percentual = 0;
        fase = "preparando";
        etapa = "lendo o vídeo";
        titulo = "";
        erro = "";
        mensagem = "";
        qualidade = rotuloQualidade == null ? "" : rotuloQualidade;

        Thread t = new Thread(() -> baixar(limpa, rotuloQualidade), "nexus-video-yt");
        t.setDaemon(true);
        t.start();
        return "{\"ok\":true}";
    }

    private void baixar(String url, String rotuloQualidade) {
        Uri destino = null;
        boolean liberado = false;
        File videoTemp = null;
        File audioTemp = null;
        try {
            preparar();
            fase = "preparando";
            etapa = "lendo o vídeo";

            StreamExtractor ex = ServiceList.YouTube.getStreamExtractor(url);
            ex.fetchPage();
            titulo = ex.getName();
            String canal = canalDe(ex);

            // ---- escolhe o stream de vídeo da qualidade pedida (ou o melhor)
            List<VideoStream> progressivos = ex.getVideoStreams();
            List<VideoStream> adaptativos = ex.getVideoOnlyStreams();
            List<QualidadeVideo.Opcao> opcoes = QualidadeVideo.montar(progressivos, adaptativos);

            QualidadeVideo.Opcao escolhida = null;
            if (rotuloQualidade != null && !rotuloQualidade.isEmpty()) {
                for (QualidadeVideo.Opcao o : opcoes)
                    if (o.rotulo.equals(rotuloQualidade)) { escolhida = o; break; }
            }
            if (escolhida == null) escolhida = QualidadeVideo.melhor(opcoes);
            if (escolhida == null) throw new IOException("Não encontrei vídeo para baixar");

            qualidade = escolhida.rotulo;
            /* O container tem de seguir o CODEC: MP4 só aceita H.264; VP9
               (as resoluções acima de 1080p) exige WebM. Salvar VP9 dentro de
               .mp4 gera arquivo que abre em poucos reprodutores. */
            boolean mp4 = escolhida.mp4;
            String container = mp4 ? "mp4" : "webm";
            String mime = mp4 ? "video/mp4" : "video/webm";
            Log.i(TAG, "baixando " + escolhida.rotulo + " (" + escolhida.tipoJson()
                    + ", " + container + ")");

            // ---- áudio: só é necessário quando o vídeo vem sem (JUNTAR)
            AudioStream audio = null;
            if (escolhida.tipo == QualidadeVideo.Tipo.JUNTAR) {
                audio = melhorAudio(ex.getAudioStreams(), mp4);
                if (audio == null) throw new IOException("Vídeo sem faixa de áudio disponível");
            }

            // ---- destino no MediaStore
            String nomeArquivo = nomeSeguro(titulo) + "." + container;
            destino = criarDestino(nomeArquivo, mime);

            long totalVideo = descobrirTamanho(escolhida.video.getUrl());
            long totalAudio = audio == null ? 0 : descobrirTamanho(audio.getUrl());
            long totalGeral = totalVideo + totalAudio;
            if (totalGeral <= 0) totalGeral = 1;

            if (escolhida.tipo == QualidadeVideo.Tipo.DIRETA) {
                fase = "baixando";
                etapa = "baixando o vídeo";
                try (OutputStream saida = ctx.getContentResolver().openOutputStream(destino)) {
                    if (saida == null) throw new IOException("Não consegui gravar o arquivo");
                    baixarParaStream(escolhida.video.getUrl(), saida, 0, totalGeral);
                }
            } else {
                // baixa os dois em arquivos temporários e junta no fim
                videoTemp = new File(ctx.getCacheDir(), "nexus_v_" + System.nanoTime() + ".mp4");
                audioTemp = new File(ctx.getCacheDir(), "nexus_a_" + System.nanoTime() + ".m4a");

                fase = "baixando";
                etapa = "baixando o vídeo";
                try (OutputStream saida = new BufferedOutputStream(
                        new FileOutputStream(videoTemp), 128 * 1024)) {
                    baixarParaStream(escolhida.video.getUrl(), saida, 0, totalGeral);
                }

                etapa = "baixando o áudio";
                try (OutputStream saida = new BufferedOutputStream(
                        new FileOutputStream(audioTemp), 128 * 1024)) {
                    baixarParaStream(audio.getUrl(), saida, totalVideo, totalGeral);
                }

                fase = "juntando";
                etapa = "juntando vídeo e áudio";
                percentual = 99;
                try (OutputStream saida = ctx.getContentResolver().openOutputStream(destino)) {
                    if (saida == null) throw new IOException("Não consegui gravar o arquivo");
                    juntar(videoTemp, audioTemp, saida, mp4);
                }
            }

            percentual = 100;
            fase = "finalizando";
            etapa = "finalizando arquivo";
            liberarPendente(destino);
            liberado = true;

            ContentValues tags = new ContentValues();
            tags.put(MediaStore.Video.Media.TITLE, titulo);
            tags.put(MediaStore.Video.Media.DISPLAY_NAME, nomeArquivo);
            try { ctx.getContentResolver().update(destino, tags, null, null); }
            catch (Exception ignored) { }

            library.recarregar();
            fase = "concluido";
            etapa = "";
            mensagem = "\u2714 " + titulo + " (" + qualidade + ")";

        } catch (Exception e) {
            if (destino != null && !liberado) {
                try { ctx.getContentResolver().delete(destino, null, null); }
                catch (Exception ignored) { }
            }
            Log.w(TAG, "falha no download: " + e, e);
            fase = cancelarSolicitado ? "cancelado" : "erro";
            erro = cancelarSolicitado ? "Download cancelado" : mensagemDeErro(e);
            mensagem = erro;
        } finally {
            if (videoTemp != null) videoTemp.delete();
            if (audioTemp != null) audioTemp.delete();
            ativo = false;
        }
    }

    /**
     * Escolhe a melhor faixa de áudio para juntar.
     *
     * <p>Aqui vale uma regra que não estava clara antes: para <b>MP4</b> o áudio
     * precisa ser <b>AAC</b>; para <b>WebM</b> precisa ser <b>Opus</b>. Misturar
     * (AAC dentro de WebM, ou Opus dentro de MP4) produz um arquivo que o próprio
     * app abre, mas outros reprodutores tocam sem som ou nem abrem.</p>
     */
    static AudioStream melhorAudio(List<AudioStream> audios, boolean mp4) {
        if (audios == null || audios.isEmpty()) return null;
        AudioStream compativel = null;
        AudioStream outro = null;
        for (AudioStream a : audios) {
            boolean aceito = mp4 ? ehAac(a) : ehOpus(a);
            if (aceito) {
                if (compativel == null || bitrate(a) > bitrate(compativel)) compativel = a;
            } else {
                if (outro == null || bitrate(a) > bitrate(outro)) outro = a;
            }
        }
        // o compatível sempre vence; o outro é só o último recurso
        return compativel != null ? compativel : outro;
    }

    private static boolean ehAac(AudioStream a) {
        return a.getFormat() == org.schabi.newpipe.extractor.MediaFormat.M4A;
    }

    private static boolean ehOpus(AudioStream a) {
        String mime = "";
        try {
            mime = a.getFormat().getMimeType();
        } catch (Exception ignored) { }
        return a.getFormat() == org.schabi.newpipe.extractor.MediaFormat.WEBMA_OPUS
                || (mime != null && mime.contains("opus"));
    }

    private static int bitrate(AudioStream a) {
        int v = a.getAverageBitrate();
        if (v > 0) return v;
        v = a.getBitrate();
        return v > 0 ? v : -1;
    }

    // ------------------------------------------------------------------ //
    //  Transferência (mesmo caminho comprovado do NEXUS MUSIC)
    // ------------------------------------------------------------------ //

    /**
     * Baixa para um stream, somando 5 conexões paralelas quando o servidor
     * suporta Range. Mesmo desenho do downloader que funciona no NEXUS MUSIC:
     * descobre o tamanho por `Range: bytes=0-0` e NÃO valida o cabeçalho de cada
     * parte (essa validação foi o que travou o download no outro app).
     */
    private void baixarParaStream(String url, OutputStream saida, long jaBaixado,
                                 long totalGeral) throws Exception {
        long total = descobrirTamanho(url);
        if (total <= 0 || total < 1024 * 1024) {
            try (InputStream entrada = abrirStream(url)) {
                copiar(entrada, saida, jaBaixado, totalGeral);
            }
            return;
        }

        final int conexoes = 5;
        final long parte = total / conexoes + 1;
        final AtomicLong baixado = new AtomicLong(0);
        final AtomicReference<String> falha = new AtomicReference<>(null);
        final List<File> partes = new ArrayList<>();
        final List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < conexoes; i++) {
            final long inicio = i * parte;
            final long fim = Math.min(inicio + parte - 1, total - 1);
            if (inicio >= total) break;
            final File tmp = new File(ctx.getCacheDir(),
                    "nexus_p_" + i + "_" + System.nanoTime());
            partes.add(tmp);
            Thread t = new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    c = abrirConexao(url);
                    c.setRequestProperty("Range", "bytes=" + inicio + "-" + fim);
                    long rec = 0;
                    try (InputStream in = new BufferedInputStream(c.getInputStream(), 128 * 1024);
                         OutputStream out = new BufferedOutputStream(
                                 new FileOutputStream(tmp), 128 * 1024)) {
                        byte[] b = new byte[128 * 1024];
                        int n;
                        while ((n = in.read(b)) > 0) {
                            if (cancelarSolicitado) throw new IOException("cancelado");
                            out.write(b, 0, n);
                            rec += n;
                            baixado.addAndGet(n);
                            publicarProgresso(jaBaixado + baixado.get(), totalGeral);
                        }
                    }
                    if (rec != fim - inicio + 1)
                        throw new IOException("parte incompleta " + rec + "/" + (fim - inicio + 1));
                } catch (Exception e) {
                    falha.compareAndSet(null, String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.disconnect();
                }
            }, "nexus-vid-parte-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) t.join();

        if (falha.get() != null) {
            for (File f : partes) f.delete();
            throw new IOException(falha.get());
        }

        try {
            for (File p : partes) {
                try (InputStream is = new BufferedInputStream(
                        new FileInputStream(p), 128 * 1024)) {
                    byte[] b = new byte[128 * 1024];
                    int n;
                    while ((n = is.read(b)) > 0) {
                        if (cancelarSolicitado) throw new IOException("cancelado");
                        saida.write(b, 0, n);
                    }
                }
            }
        } finally {
            for (File f : partes) f.delete();
        }
    }

    private void copiar(InputStream entrada, OutputStream saida,
                        long jaBaixado, long totalGeral) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long rec = 0;
        int n;
        while ((n = entrada.read(buffer)) > 0) {
            if (cancelarSolicitado) throw new IOException("cancelado");
            saida.write(buffer, 0, n);
            rec += n;
            publicarProgresso(jaBaixado + rec, totalGeral);
        }
    }

    private void publicarProgresso(long jaFeito, long total) {
        if (total <= 0) return;
        double p = Math.min(99.0, jaFeito * 100.0 / total);
        if (p > percentual) percentual = p;
    }

    /** Descobre o tamanho sem baixar tudo (Range de 1 byte). */
    private long descobrirTamanho(String url) {
        HttpURLConnection c = null;
        try {
            c = abrirConexao(url);
            c.setRequestProperty("Range", "bytes=0-0");
            int codigo = c.getResponseCode();
            if (codigo == 206) {
                String faixa = c.getHeaderField("Content-Range");
                if (faixa != null && faixa.contains("/")) {
                    return Long.parseLong(faixa.substring(faixa.indexOf('/') + 1).trim());
                }
            }
            return c.getContentLengthLong();
        } catch (Exception e) {
            Log.w(TAG, "tamanho: " + e.getMessage());
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private InputStream abrirStream(String url) throws IOException {
        HttpURLConnection c = abrirConexao(url);
        return new BufferedInputStream(c.getInputStream(), 128 * 1024);
    }

    /** Conexão padronizada (UA de navegador + sem compressão, senão o Range quebra). */
    private HttpURLConnection abrirConexao(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/110.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    // ------------------------------------------------------------------ //
    //  Junção vídeo + áudio (MediaMuxer, sem reencode)
    // ------------------------------------------------------------------ //

    /**
     * Junta um arquivo só de vídeo com um só de áudio no container indicado,
     * SEM reencodar.
     *
     * <p><b>O ponto crítico:</b> o {@link MediaMuxer} exige que as amostras
     * cheguem em ordem CRESCENTE de tempo, considerando TODAS as pistas juntas.
     * Escrever o vídeo inteiro e depois o áudio inteiro gera um arquivo que
     * abre e mostra imagem, mas <b>não toca o som</b> em outros reprodutores —
     * porque todos os tempos do áudio vêm depois dos tempos do vídeo.</p>
     *
     * <p>Por isso as amostras são ENTRELAÇADAS: a cada passo escreve-se a que
     * tiver o menor tempo entre as duas pistas. Também se normaliza cada pista
     * para começar em zero (as duas podem ter deslocamento inicial diferente,
     * o que dessincronizaria o som).</p>
     *
     * @param mp4 {@code true} para MP4 (H.264+AAC), {@code false} para WebM
     *            (VP9+Opus, usado pelas resoluções acima de 1080p)
     */
    private void juntar(File video, File audio, OutputStream destino, boolean mp4)
            throws IOException {
        MediaExtractor eV = new MediaExtractor();
        MediaExtractor eA = new MediaExtractor();
        MediaMuxer muxer = null;
        File tmp = File.createTempFile("nexus_mux_", mp4 ? ".mp4" : ".webm");
        try {
            eV.setDataSource(video.getAbsolutePath());
            eA.setDataSource(audio.getAbsolutePath());

            int vIdx = pistaPrincipal(eV, "video/");
            int aIdx = pistaPrincipal(eA, "audio/");
            if (vIdx < 0) throw new IOException("arquivo de vídeo sem faixa de imagem");
            if (aIdx < 0) throw new IOException("arquivo de áudio sem faixa de som");

            eV.selectTrack(vIdx);
            eA.selectTrack(aIdx);

            muxer = new MediaMuxer(tmp.getAbsolutePath(), mp4
                    ? MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    : MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM);
            int vOut = muxer.addTrack(eV.getTrackFormat(vIdx));
            int aOut = muxer.addTrack(eA.getTrackFormat(aIdx));
            muxer.start();

            /* deslocamento inicial de cada pista, para as duas começarem em zero */
            long baseV = eV.getSampleTime();
            long baseA = eA.getSampleTime();
            if (baseV < 0) baseV = 0;
            if (baseA < 0) baseA = 0;

            java.nio.ByteBuffer bufferV = bufferPara(eV, vIdx);
            java.nio.ByteBuffer bufferA = bufferPara(eA, aIdx);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            long tv = eV.getSampleTime();
            long ta = eA.getSampleTime();

            while (tv >= 0 || ta >= 0) {
                if (cancelarSolicitado) throw new IOException("cancelado");
                /* escreve primeiro a amostra de menor tempo (entrelaça as pistas) */
                if (tv >= 0 && (ta < 0 || tv <= ta)) {
                    int n = eV.readSampleData(bufferV, 0);
                    if (n < 0) break;
                    info.offset = 0;
                    info.size = n;
                    info.presentationTimeUs = tv - baseV;
                    info.flags = (eV.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    muxer.writeSampleData(vOut, bufferV, info);
                    eV.advance();
                    tv = eV.getSampleTime();
                } else {
                    int n = eA.readSampleData(bufferA, 0);
                    if (n < 0) break;
                    info.offset = 0;
                    info.size = n;
                    info.presentationTimeUs = ta - baseA;
                    info.flags = 0;
                    muxer.writeSampleData(aOut, bufferA, info);
                    eA.advance();
                    ta = eA.getSampleTime();
                }
            }

            muxer.stop();
            muxer.release();
            muxer = null;

            try (InputStream in = new BufferedInputStream(new FileInputStream(tmp), 128 * 1024)) {
                byte[] b = new byte[128 * 1024];
                int n;
                while ((n = in.read(b)) > 0) destino.write(b, 0, n);
            }
        } finally {
            if (muxer != null) {
                try { muxer.stop(); } catch (Exception ignored) { }
                try { muxer.release(); } catch (Exception ignored) { }
            }
            eV.release();
            eA.release();
            tmp.delete();
        }
    }

    /**
     * Buffer do tamanho que a pista pede. Um quadro-chave de 4K pode passar de
     * 1 MB; usar o tamanho declarado no formato (quando existe) evita leitura
     * truncada, e a folga cobre formatos que não informam.
     */
    private static java.nio.ByteBuffer bufferPara(MediaExtractor ex, int pista) {
        int tamanho = 2 * 1024 * 1024;
        try {
            MediaFormat f = ex.getTrackFormat(pista);
            if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                int declarado = f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                if (declarado > 0) tamanho = declarado + 64 * 1024;
            }
        } catch (Exception ignored) { }
        return java.nio.ByteBuffer.allocateDirect(tamanho);
    }

    private static int pistaPrincipal(MediaExtractor ex, String prefixo) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith(prefixo)) {
                return i;
            }
        }
        return -1;
    }

    /** Copia todas as amostras de uma pista (cópia direta, sem decodificar). */
    private static void copiarPista(MediaExtractor ex, int pista, MediaMuxer muxer,
                                    int saida) {
        ex.selectTrack(pista);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(1024 * 1024);
        while (true) {
            int tamanho = ex.readSampleData(buffer, 0);
            if (tamanho < 0) break;
            info.offset = 0;
            info.size = tamanho;
            info.presentationTimeUs = ex.getSampleTime();
            /* O flag de amostra do MediaExtractor usa a MESMA constante do
               MediaCodec para "quadro-chave" (ambos valem 1); traduzir evita
               passar um conjunto de flags que não pertence ao BufferInfo. */
            info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                    : 0;
            muxer.writeSampleData(saida, buffer, info);
            ex.advance();
        }
        ex.unselectTrack(pista);
    }

    // ------------------------------------------------------------------ //
    //  MediaStore
    // ------------------------------------------------------------------ //

    private Uri criarDestino(String nomeArquivo, String tipo) {
        ContentResolver resolver = ctx.getContentResolver();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues valores = new ContentValues();
            valores.put(MediaStore.Video.Media.DISPLAY_NAME, nomeArquivo);
            valores.put(MediaStore.Video.Media.MIME_TYPE, tipo);
            valores.put(MediaStore.Video.Media.RELATIVE_PATH, VideoLibrary.SUBPASTA);
            valores.put(MediaStore.Video.Media.IS_PENDING, 1);

            Uri colecao = MediaStore.Video.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = resolver.insert(colecao, valores);
            if (uri == null) throw new IllegalStateException("MediaStore recusou o arquivo");
            Log.i(TAG, "destino RELATIVE_PATH: " + VideoLibrary.SUBPASTA);
            return uri;
        }

        // Android 9 ou anterior: grava direto na pasta pública
        File pasta = VideoLibrary.pastaPublica();
        File arquivo = new File(pasta, nomeArquivo);
        return Uri.fromFile(arquivo);
    }

    private void liberarPendente(Uri destino) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues pronto = new ContentValues();
            pronto.put(MediaStore.Video.Media.IS_PENDING, 0);
            try { ctx.getContentResolver().update(destino, pronto, null, null); }
            catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ //
    //  Utilidades
    // ------------------------------------------------------------------ //

    private String mensagemDeErro(Exception e) {
        String texto = String.valueOf(e.getMessage());
        if (texto.contains("reCaptcha") || texto.contains("Recaptcha"))
            return "O YouTube pediu verificação — tente outro vídeo";
        if (texto.contains("limitou o IP") || texto.contains("429"))
            return "O YouTube limitou o IP — aguarde alguns minutos e tente de novo";
        if (texto.contains("Unable to resolve host") || texto.contains("UnknownHost"))
            return "Sem conexão com a internet";
        return texto.length() > 140 ? texto.substring(0, 140) : texto;
    }

    static String formatarDuracao(long segundos) {
        if (segundos <= 0) return "";
        long h = segundos / 3600;
        long m = (segundos % 3600) / 60;
        long s = segundos % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    static String nomeSeguro(String texto) {
        if (texto == null || texto.trim().isEmpty()) return "video";
        String limpo = texto.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        if (limpo.length() > 90) limpo = limpo.substring(0, 90).trim();
        return limpo.isEmpty() ? "video" : limpo;
    }
}
