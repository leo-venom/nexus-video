package com.leo.nexusvideo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlayerService extends Service {

    private static final String TAG = "NexusVideoPlay";
    private static final String CANAL_PLAYER = "nexus_video_player";
    private static final String CANAL_DOWNLOAD = "nexus_video_download";
    private static final int ID_PLAYER = 8577;
    private static final int ID_DOWNLOAD = 8578;
    private static final int ID_NEUTRA = 8579;
    private static final long INTERVALO_MS = 1000L;
    private static final long INTERVALO_FUNDO_MS = 5000L;
    private static final long OCIOSO_MS = 15000L;

    public static final String ACAO_TOGGLE = "com.leo.nexusvideo.TOGGLE";
    public static final String ACAO_PROXIMA = "com.leo.nexusvideo.PROXIMA";
    public static final String ACAO_ANTERIOR = "com.leo.nexusvideo.ANTERIOR";
    public static final String ACAO_CANCELAR = "com.leo.nexusvideo.CANCELAR";
    // Ações internas
    static final String ACAO_INICIAR_FUNDO = "com.leo.nexusvideo.INICIAR_FUNDO";
    static final String ACAO_PARAR_FUNDO = "com.leo.nexusvideo.PARAR_FUNDO";
    static final String ACAO_TOCAR = "com.leo.nexusvideo.TOCAR";
    static final String ACAO_CONTROLE = "com.leo.nexusvideo.CONTROLE";

    private static final String EXTRA_TITULO = "nexus.titulo";
    private static final String EXTRA_SUB = "nexus.sub";
    private static final String EXTRA_TOCANDO = "nexus.tocando";
    private static final String EXTRA_POS = "nexus.posicao";
    private static final String EXTRA_DUR = "nexus.duracao";
    private static final String EXTRA_FONTE_TIPO = "nexus.fonte_tipo";
    private static final String EXTRA_FONTE_ID = "nexus.fonte_id";
    private static final String EXTRA_FONTE_URL = "nexus.fonte_url";
    private static final String EXTRA_AUTOPLAY = "nexus.autoplay";
    private static final String EXTRA_ACAO_CONTROLE = "nexus.controle";
    private static final String EXTRA_POSICAO_SEEK = "nexus.seek";
    private static volatile Surface surfacePendente;

    private static volatile VideoLibrary biblioteca;

    // Última fonte informada pelo WebView
    private static volatile String ultFonteTipo = "";
    private static volatile String ultFonteId = "";
    private static volatile String ultFonteUrl = "";
    private static volatile String ultTitulo = "";
    private static volatile String ultSub = "";
    private static volatile long ultPosMs = 0;
    private static volatile long ultDurMs = 0;
    private static volatile boolean ultTocando = false;
    private static final PlaybackLifecycleGuard playbackLifecycleGuard = new PlaybackLifecycleGuard();

    // Fila
    private static volatile String filaJson = "";
    private static volatile int indiceNaFila = -1;
    /** Dados que o JS mandou antes de o serviço existir (leitura no onCreate). */
    private static volatile String pendenteFonteJson = "";
    private static volatile String pendenteFilaJson = "";
    private static volatile int pendenteIndice = -1;

    private static volatile boolean appVisivel;
    /** true enquanto o app está fora da tela (o nativo deve tocar). */
    private static volatile boolean deveEstarEmFundo;
    private static volatile PlayerService instancia;

    private MediaSession sessao;
    private final Handler relogio = new Handler(Looper.getMainLooper());

    private String titulo = "";
    private String sub = "";
    private boolean tocando;
    private long posicaoMs;
    private long duracaoMs;

    // Player nativo
    private MediaPlayer playerFundo;
    private boolean playerPronto;
    /** Esperando o WebView soltar o áudio (ou o timeout, se ele já congelou).
        Estático porque o serviço pode nascer depois do handoff. */
    private static volatile boolean cedeAguardando;
    /** O WebView entregou a posição exata — não estimar mais. */
    private static volatile boolean cedeRecebido;
    private static volatile boolean posReferenciaValida;
    private static volatile long referenciaTempo;
    private int itensNaFila = 0;
    private int itemAtualNaFila = -1;

    private AudioManager audioMgr;
    private AudioFocusRequest pedidoFoco;

    private boolean emForeground;
    private boolean precisaForeground;
    private long ociosoDesde;
    private boolean downloadEstavaAtivo;
    private String ultimoTituloDownload = "";

    /** Aguardando o WebView confirmar que voltou a reproduzir. */
    private boolean retornoAguardando;
    private boolean retomarAposFocoTransient;
    private static final long CEDE_TIMEOUT_MS = 1200L;
    /**
     * O WebView foi avisado para soltar o áudio e ainda não respondeu.
     * Se ele já estiver congelado (tela apagada), o timeout assume sozinho.
     */
    private final Runnable timeoutCede = () -> {
        if (!cedeAguardando) return;
        Log.i(TAG, "cede: WebView nao respondeu — nativo assume");
        assumir();
    };

    /** O nativo toma o áudio no ponto exato (sem silêncio entre os players). */
    private void assumir() {
        cedeAguardando = false;
        handler.removeCallbacks(timeoutCede);
        if (playerFundo == null || !playerPronto) return;
        if (!deveEstarEmFundo) return;
        try {
            long alvo = ultPosMs;
            /* Sem resposta do WebView (ele foi congelado no onStop), o áudio
               dele parou naquele instante: somar o tempo decorrido põe o nativo
               no mesmo ponto — sem isso a reprodução voltava alguns segundos.
               Com resposta (cedeRecebido), a posição é exata e não se estima. */
            if (posReferenciaValida && !cedeRecebido) {
                long decorrido = android.os.SystemClock.elapsedRealtime() - referenciaTempo;
                alvo += Math.max(0, decorrido);
            }
            if (duracaoMs > 0) alvo = Math.min(alvo, Math.max(0, duracaoMs - 300));

            int atual = playerFundo.getCurrentPosition();
            if (alvo > 0 && Math.abs(atual - alvo) > 400) {
                aguardandoSeekAssumir = true;
                playerFundo.seekTo((int) Math.min(alvo, Integer.MAX_VALUE));
                handler.removeCallbacks(timeoutSeekAssumir);
                handler.postDelayed(timeoutSeekAssumir, 900L);
                return;
            }
            iniciarAudioFundo(alvo);
        } catch (Exception e) {
            Log.w(TAG, "assumir: " + e);
        }
    }

    /** Sobe o volume e dá play: só depois do seek, para não tocar no ponto errado. */
    private void iniciarAudioFundo(long onde) {
        aguardandoSeekAssumir = false;
        handler.removeCallbacks(timeoutSeekAssumir);
        if (playerFundo == null || !playerPronto || !deveEstarEmFundo) return;
        try {
            playerFundo.setVolume(1f, 1f);
            if (!playerFundo.isPlaying()) playerFundo.start();
            tocando = true;
            retomarAposFocoTransient = false;
            sessao.setPlaybackState(estado(true));
            Log.i(TAG, "fundos: tocando em " + onde + "ms");
        } catch (Exception e) {
            Log.w(TAG, "iniciarAudioFundo: " + e);
        }
    }
    /** Esperando o seek do nativo terminar antes de subir o volume. */
    private boolean aguardandoSeekAssumir;
    private final Runnable timeoutSeekAssumir = () -> {
        if (!aguardandoSeekAssumir) return;
        int p = playerFundo != null ? playerFundo.getCurrentPosition() : 0;
        Log.i(TAG, "seek do nativo demorou — sobe o audio em " + p + "ms");
        iniciarAudioFundo(p);
    };

    /** Se o WebView não retomar, devolve o áudio ao nativo (sem parada audível). */
    private final Runnable timeoutRetorno = () -> {
        if (!retornoAguardando) return;
        retornoAguardando = false;
        /* O app está na tela: se o WebView não confirmou, o nativo sai em vez de
           devolver o áudio — devolver criaria dois áudios somados (eco). */
        if (!deveEstarEmFundo || appVisivel) {
            Log.i(TAG, "retorno: app na tela — nativo liberado sem eco");
            pararPlayerFundo();
            return;
        }
        if (playerFundo != null && playerPronto) {
            try {
                playerFundo.setVolume(1f, 1f);
                if (!playerFundo.isPlaying()) playerFundo.start();
                tocando = true;
                sessao.setPlaybackState(estado(true));
                Log.i(TAG, "retorno: WebView nao retomou — nativo devolve o audio");
            } catch (Exception e) {
                Log.w(TAG, "timeoutRetorno: " + e);
            }
        }
    };

    private final Runnable tique = new Runnable() {
        @Override
        public void run() {
            passo();
            if (relogio != null) agendar();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instancia = this;
        criarCanais();
        audioMgr = (AudioManager) getSystemService(AUDIO_SERVICE);

        sessao = new MediaSession(this, "NEXUS VIDEO");
        sessao.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        sessao.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { cmd("play"); }
            @Override public void onPause() { cmd("pause"); }
            @Override public void onStop() { cmd("pause"); }
            @Override public void onSkipToNext() {
                cmd("next");
            }
            @Override public void onSkipToPrevious() { cmd("prev"); }
            @Override public void onSeekTo(long alvo) {
                if (playerFundo != null && playerPronto) {
                    playerFundo.seekTo((int) Math.max(0, Math.min(alvo, Integer.MAX_VALUE)));
                } else {
                    MainActivity.chamarJs("window.nexusControle&&window.nexusControle('seek'," + alvo + ")");
                }
            }
        });
        sessao.setActive(true);
        sessao.setPlaybackState(estado(false));
        Log.i(TAG, "MediaSession ativa");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        precisaForeground = true;
        String acao = intent == null ? null : intent.getAction();

        if (ACAO_TOGGLE.equals(acao)) cmd("toggle");
        else if (ACAO_PROXIMA.equals(acao)) avançarFila();
        else if (ACAO_ANTERIOR.equals(acao)) cmd("prev");
        else if (ACAO_CANCELAR.equals(acao) && baixador != null) baixador.cancelar();
        else if (ACAO_INICIAR_FUNDO.equals(acao)) aplicarPendente();
        else if (acao != null && acao.startsWith("com.leo.nexusvideo.FONE.")) {
            String fone = acao.substring("com.leo.nexusvideo.FONE.".length());
            if ("next".equals(fone)) avançarFila(); else cmd(fone);
        }

        if (intent != null && intent.hasExtra(EXTRA_TOCANDO)) {
            ultFonteTipo = intent.getStringExtra(EXTRA_FONTE_TIPO) == null ? "" : intent.getStringExtra(EXTRA_FONTE_TIPO);
            ultFonteId = intent.getStringExtra(EXTRA_FONTE_ID) == null ? "" : intent.getStringExtra(EXTRA_FONTE_ID);
            ultFonteUrl = intent.getStringExtra(EXTRA_FONTE_URL) == null ? "" : intent.getStringExtra(EXTRA_FONTE_URL);
            aplicar(intent.getStringExtra(EXTRA_TITULO),
                    intent.getStringExtra(EXTRA_SUB),
                    intent.getBooleanExtra(EXTRA_TOCANDO, false),
                    intent.getLongExtra(EXTRA_POS, 0L),
                    intent.getLongExtra(EXTRA_DUR, 0L));
        }

        relogio.removeCallbacks(tique);
        agendar();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        relogio.removeCallbacks(tique);
        pararPlayerFundo();
        instancia = null;
        if (sessao != null) { sessao.setActive(false); sessao.release(); sessao = null; }
        super.onDestroy();
        Log.i(TAG, "servico encerrado");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // -------------------------------------------------------------- estáticas

    public static void registrar(VideoLibrary lib, YtVideoDownload b) {
        biblioteca = lib; baixador = b;
    }

    /** Atualiza a fonte e metadados. Chamado pelo WebView via ponte Java. */
    public static void setFonte(Context ctx, String tipo, String id, String url,
                                String titulo, String sub, long posMs, long durMs, boolean tocando) {
        final String t = tipo == null ? "" : tipo;
        final String i = id == null ? "" : id;
        final String u = url == null ? "" : url;

        PlaybackLifecycleGuard.Snapshot pauseSnapshot = playbackLifecycleGuard.pauseUpdateToIgnore(tocando);
        if (pauseSnapshot != null) {
            ultFonteTipo = pauseSnapshot.type;
            ultFonteId = pauseSnapshot.id;
            ultFonteUrl = pauseSnapshot.url;
            ultTitulo = pauseSnapshot.title;
            ultSub = pauseSnapshot.subtitle;
            ultPosMs = pauseSnapshot.positionMs;
            ultDurMs = pauseSnapshot.durationMs;
            ultTocando = true;
            Log.i(TAG, "pause do WebView ignorado durante handoff de lifecycle");
            return;
        }
        playbackLifecycleGuard.record(new PlaybackLifecycleGuard.Snapshot(
                t, i, u, titulo, sub, posMs, durMs, tocando));

        PlayerService s = instancia;
        if (s != null) {
            /* Serviço vivo: chamada direta, nunca um Intent. Um
               `startForegroundService` disparado do segundo plano é bloqueado no
               Android 12+ — era exatamente ele que aparecia quando o evento
               `pause` do WebView chegava depois do handoff, e derrubava o nativo. */
            s.handler.post(() -> s.aplicarFonte(t, i, u, titulo, sub, posMs, durMs, tocando));
            return;
        }

        /* Sem serviço vivo só é possível subir se o app estiver na tela. */
        if (!appVisivel) {
            ultFonteTipo = t; ultFonteId = i; ultFonteUrl = u;
            if (titulo != null) ultTitulo = titulo;
            if (sub != null) ultSub = sub;
            ultPosMs = Math.max(0, posMs);
            ultDurMs = Math.max(0, durMs);
            ultTocando = tocando;
            return;
        }

        ultFonteTipo = t;
        ultFonteId = i;
        ultFonteUrl = u;
        ultTitulo = titulo == null ? "" : titulo;
        ultSub = sub == null ? "" : sub;
        ultPosMs = Math.max(0, posMs);
        ultDurMs = Math.max(0, durMs);
        ultTocando = tocando;
        Intent intent = new Intent(ctx, PlayerService.class);
        intent.putExtra(EXTRA_TOCANDO, tocando);
        intent.putExtra(EXTRA_TITULO, ultTitulo);
        intent.putExtra(EXTRA_SUB, ultSub);
        intent.putExtra(EXTRA_POS, ultPosMs);
        intent.putExtra(EXTRA_DUR, ultDurMs);
        intent.putExtra(EXTRA_FONTE_TIPO, ultFonteTipo);
        intent.putExtra(EXTRA_FONTE_ID, ultFonteId);
        intent.putExtra(EXTRA_FONTE_URL, ultFonteUrl);
        disparar(ctx, intent);
    }

    /**
     * Aplica a fonte vinda do WebView sem interromper o player nativo.
     *
     * <p>O WebView dispara `pause`/`tocando(false)` logo depois do handoff (ele
     * acabou de pausar o `&lt;video&gt;`). Tratar isso como "parou" matava o nativo
     * recém-criado — era o corte na reprodução ao entrar em segundo plano.</p>
     */
    private void aplicarFonte(String tipo, String id, String url,
                              String tituloNv, String subNv,
                              long posMs, long durMs, boolean estaTocando) {
        PlaybackLifecycleGuard.Snapshot pauseSnapshot =
                playbackLifecycleGuard.pauseUpdateToIgnore(estaTocando);
        if (pauseSnapshot != null) {
            ultFonteTipo = pauseSnapshot.type;
            ultFonteId = pauseSnapshot.id;
            ultFonteUrl = pauseSnapshot.url;
            ultTitulo = pauseSnapshot.title;
            ultSub = pauseSnapshot.subtitle;
            ultPosMs = pauseSnapshot.positionMs;
            ultDurMs = pauseSnapshot.durationMs;
            ultTocando = true;
            Log.i(TAG, "pause do WebView ignorado no player nativo durante handoff");
            return;
        }

        /* Fonte vazia com o nativo tocando = eco de pause do WebView no handoff. */
        if (playerFundo != null && deveEstarEmFundo
                && tipo.isEmpty() && id.isEmpty() && url.isEmpty()) {
            Log.i(TAG, "fonte vazia ignorada: nativo segue tocando");
            return;
        }

        /* Fonte anterior, antes de sobrescrever — é o que diz se o nativo
           ainda serve ou precisa ser recriado. */
        final boolean fonteMudou = playerFundo != null
                && !(ultFonteTipo.equals(tipo) && ultFonteId.equals(id) && ultFonteUrl.equals(url));

        ultFonteTipo = tipo; ultFonteId = id; ultFonteUrl = url;
        if (tituloNv != null && !tituloNv.isEmpty()) ultTitulo = tituloNv;
        if (subNv != null && !subNv.isEmpty()) ultSub = subNv;
        ultDurMs = Math.max(0, durMs);

        /* Com o nativo tocando, "pause" vindo do WebView é só o eco do handoff
           (o <video> acabou de pausar): não é ordem de parada. */
        if (playerFundo != null && deveEstarEmFundo) {
            ultTocando = true;
        } else {
            ultTocando = estaTocando;
        }

        if (playerFundo == null) {
            posicaoMs = Math.max(0, posMs);
            ultPosMs = posicaoMs;
            /* Em pleno handoff o estado real é "tocando": manter assim evita a
               notificação piscar play → pause → play no bloqueio da tela. */
            if (!deveEstarEmFundo) tocando = estaTocando;
            if (sessao != null) atualizarMetadados();
            return;
        }

        /* Nativo assumiu: ele manda na posição. Só fonte diferente justifica
           recriar o player. */
        if (fonteMudou) {
            Log.i(TAG, "fonte mudou no nativo: " + ultTitulo);
            pararPlayerFundo();
            handler.postDelayed(this::iniciarPlayerFundo, 120);
            return;
        }

        if (!estaTocando && playerFundo.isPlaying()) {
            if (!deveEstarEmFundo) {
                /* App na tela: é ordem real de pausa. */
                playerFundo.pause();
                tocando = false;
                if (sessao != null) sessao.setPlaybackState(estado(false));
            } else {
                Log.i(TAG, "pause do WebView ignorado: nativo segue (handoff)");
            }
        } else if (estaTocando && !playerFundo.isPlaying() && playerPronto) {
            playerFundo.start();
            tocando = true;
            if (sessao != null) sessao.setPlaybackState(estado(true));
        }
        if (sessao != null) atualizarMetadados();
    }

    /** Armazena a fila. */
    public static void setFila(String json, int indice) {
        filaJson = json == null ? "" : json;
        indiceNaFila = indice;
    }

    /** Quando o app sai da tela: inicia o player nativo. */
    public static void sairDaTela(Context ctx) {
        deveEstarEmFundo = true;
        PlayerService s = instancia;
        if (s != null) {
            /* Serviço já vivo: chamada direta. `startForegroundService` com o app
               já em segundo plano é bloqueado pelo Android 12+ e era o que fazia
               o segundo plano parar de funcionar. */
            s.handler.post(s::iniciarPlayerFundo);
            return;
        }
        Intent i = new Intent(ctx, PlayerService.class).setAction(ACAO_INICIAR_FUNDO);
        disparar(ctx, i);
    }

    /**
     * Chamada única do JS quando o app sai da tela: grava fonte + fila e sobe
     * o player nativo. Assim não existe janela entre gravar e tocar — separar
     * em duas chamadas fazia o serviço subir sem a fonte.
     */
    public static void entrarEmSegundoPlano(Context ctx, String fonteJson,
                                            String filaJson, int indice) {
        /* Guarda em estáticos ANTES de qualquer coisa: se o serviço ainda não
           existir, ele lê isto no onCreate (que é assíncrono). */
        pendenteFonteJson = fonteJson == null ? "" : fonteJson;
        pendenteFilaJson = filaJson == null ? "" : filaJson;
        pendenteIndice = indice;
        deveEstarEmFundo = true;
        /* Instante em que o WebView mandou a posição: se ele não conseguir
           responder ao pedido de cessão (tela já apagada), a estimativa soma
           esse tempo. */
        referenciaTempo = android.os.SystemClock.elapsedRealtime();
        posReferenciaValida = true;
        cedeRecebido = false;

        PlayerService s = instancia;
        if (s == null) {
            cedeAguardando = true;
            Intent i = new Intent(ctx, PlayerService.class).setAction(ACAO_INICIAR_FUNDO);
            disparar(ctx, i);
            return;
        }
        s.handler.post(() -> { s.cedeAguardando = true; s.aplicarPendente(); });
    }

    /** O WebView pausou o `&lt;video&gt;` no ponto exato e mandou a posição. */
    public static void cedeuAgora(Context ctx, long posMs) {
        PlayerService s = instancia;
        if (s == null) return;
        s.handler.post(() -> {
            s.ultPosMs = Math.max(0, posMs);
            if (!cedeAguardando) {
                /* O timeout já assumiu: só corrige a referência de posição. */
                Log.i(TAG, "cede: posicao atualizada em " + posMs + "ms");
                return;
            }
            cedeRecebido = true;
            Log.i(TAG, "cede: WebView soltou o audio em " + posMs + "ms");
            s.assumir();
        });
    }

    /** Aplica o que o JS mandou e sobe o player nativo. */
    private void aplicarPendente() {
        try {
            if (!pendenteFonteJson.isEmpty()) {
                org.json.JSONObject o = new org.json.JSONObject(pendenteFonteJson);
                ultFonteTipo = o.optString("tipo", "");
                ultFonteId = o.optString("id", "");
                ultFonteUrl = o.optString("url", "");
                ultTitulo = o.optString("titulo", "");
                ultSub = o.optString("sub", "");
                ultPosMs = (long) o.optDouble("pos", 0);
                ultDurMs = (long) o.optDouble("dur", 0);
                titulo = ultTitulo;
                sub = ultSub;
                posicaoMs = ultPosMs;
                duracaoMs = ultDurMs;
            }
            setFila(pendenteFilaJson, pendenteIndice);
            atualizarMetadados();
        } catch (Exception e) {
            Log.w(TAG, "aplicarPendente: " + e);
        }
        iniciarPlayerFundo();
    }

    /** Publica título/sub na MediaSession (notificação e tela bloqueada). */
    private void atualizarMetadados() {
        if (sessao == null) return;
        try {
            sessao.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE,
                            titulo.isEmpty() ? "NEXUS VIDEO" : titulo)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST,
                            sub.isEmpty() ? "NEXUS VIDEO" : sub)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "NEXUS VIDEO")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duracaoMs).build());
            sessao.setPlaybackState(estado(tocando));
        } catch (Exception e) {
            Log.w(TAG, "atualizarMetadados: " + e);
        }
    }

    /**
     * Quando o app volta: o nativo é silenciado (não pausado) e a posição exata
     * vai para o WebView. O nativo só é liberado depois que o WebView confirma
     * que voltou a reproduzir — assim não há silêncio nem retorno de tempo.
     */
    public static void voltarATela(Context ctx) {
        deveEstarEmFundo = false;
        cedeAguardando = false;
        PlayerService s = instancia;
        if (s != null) s.handler.removeCallbacks(s.timeoutCede);
        if (s == null || s.playerFundo == null) return;

        long pos;
        boolean tocando;
        try {
            pos = Math.max(0, s.playerFundo.getCurrentPosition());
            tocando = s.playerFundo.isPlaying() || s.tocando;
        } catch (Exception e) {
            pos = s.posicaoMs;
            tocando = s.tocando;
        }
        try {
            /* Silencia: o WebView reassume sem eco. Se ele não conseguir, o
               timeout devolve o áudio e o nativo segue de onde estava. */
            s.playerFundo.setVolume(0f, 0f);
        } catch (Exception ignored) { }
        s.retornoAguardando = true;
        s.handler.removeCallbacks(s.timeoutRetorno);
        s.handler.postDelayed(s.timeoutRetorno, 2500L);

        MainActivity.chamarJs("window.nxRestaurarPos&&window.nxRestaurarPos("
                + pos + "," + tocando + ")");
    }

    /** O WebView confirmou que voltou a reproduzir: pode liberar o nativo. */
    public static void retomouOk(Context ctx) {
        PlayerService s = instancia;
        if (s == null) return;
        s.handler.post(s::confirmarRetorno);
    }

    /** Play/pause/next/prev vindos do fone Bluetooth ou com fio. */
    public static void peloFone(Context ctx, String acao) {
        PlayerService s = instancia;
        if (s != null) {
            s.handler.post(() -> {
                if ("next".equals(acao)) s.avançarFila();
                else s.cmd(acao);
            });
            return;
        }
        Intent i = new Intent(ctx, PlayerService.class)
                .setAction("com.leo.nexusvideo.FONE." + acao);
        disparar(ctx, i);
    }

    public static boolean emSegundoPlano() {
        PlayerService s = instancia;
        return s != null && s.playerFundo != null;
    }

    public static void appEmPrimeiroPlano() {
        appVisivel = true;
        playbackLifecycleGuard.activityResumed();
        PlayerService s = instancia;
        if (s != null) {
            if (s.playerFundo != null) {
                try {
                    long pos = Math.max(0, s.playerFundo.getCurrentPosition());
                    long dur = Math.max(0, s.playerFundo.getDuration());
                    playbackLifecycleGuard.record(new PlaybackLifecycleGuard.Snapshot(
                            ultFonteTipo, ultFonteId, ultFonteUrl, ultTitulo, ultSub, pos, dur, s.playerFundo.isPlaying()));
                } catch (Exception ignored) { }
            }
            s.handler.post(s::reagendar);
        }
    }

    public static void activityVaiPausar(String titulo, String sub, boolean tocando,
                                         long posMs, long durMs, String tipo, String id, String url) {
        playbackLifecycleGuard.activityPausing(new PlaybackLifecycleGuard.Snapshot(
                tipo, id, url, titulo, sub, posMs, durMs, tocando));
    }

    /** Estado reportado pelo WebView, mantido como fallback se evaluateJavascript não completar. */
    public static void registrarEstadoLifecycle(String titulo, String sub, boolean tocando,
                                                long posMs, long durMs, String tipo, String id, String url) {
        playbackLifecycleGuard.record(new PlaybackLifecycleGuard.Snapshot(
                tipo, id, url, titulo, sub, posMs, durMs, tocando));
    }

    public static void activityEmSegundoPlano() {
        playbackLifecycleGuard.activityStopped();
        PlaybackLifecycleGuard.Snapshot salvo = playbackLifecycleGuard.backgroundSnapshot();
        if (salvo != null) {
            ultFonteTipo = salvo.type;
            ultFonteId = salvo.id;
            ultFonteUrl = salvo.url;
            ultTitulo = salvo.title;
            ultSub = salvo.subtitle;
            ultPosMs = salvo.positionMs;
            ultDurMs = salvo.durationMs;
            ultTocando = true;
            Log.i(TAG, "handoff lifecycle preservado: " + ultTitulo + " pos=" + ultPosMs);
        }
    }

    public static void pausaExplicita() {
        playbackLifecycleGuard.explicitPause();
    }

    /**
     * App saiu da tela. O JS normalmente manda tudo via {@code entrarEmSegundoPlano};
     * se o WebView congelar antes disso, este fallback garante que o nativo suba
     * com a última fonte já conhecida (sem depender do JS).
     */
    public static void appEmSegundoPlano() {
        appVisivel = false;
        final PlayerService s = instancia;
        if (s == null) return;
        s.handler.postDelayed(() -> {
            if (appVisivel) return;                 /* já voltou */
            if (s.playerFundo != null) return;      /* JS já iniciou */
            if (!ultTocando) return;                /* estava pausado: nada a fazer */
            if (ultFonteTipo.isEmpty() && ultFonteId.isEmpty() && ultFonteUrl.isEmpty()) return;
            Log.i(TAG, "fallback: JS nao chegou — nativo inicia com a ultima fonte");
            deveEstarEmFundo = true;
            s.deveEstarEmFundo = true;
            s.iniciarPlayerFundo();
        }, 400L);
    }
    public static void encerrar(Context ctx) { ctx.stopService(new Intent(ctx, PlayerService.class)); }

    // -------------------------------------------------------------- player fundo

    private void iniciarPlayerFundo() {
        if (playerFundo != null) return;
        if (ultFonteTipo.isEmpty() && ultFonteId.isEmpty() && ultFonteUrl.isEmpty()) return;

        Log.i(TAG, "iniciando player fundo: " + ultTitulo + " pos=" + ultPosMs);

        // Conta itens da fila
        itensNaFila = 0;
        itemAtualNaFila = -1;
        if (!filaJson.isEmpty()) {
            try { org.json.JSONArray arr = new org.json.JSONArray(filaJson); itensNaFila = arr.length(); } catch (Exception e) { itensNaFila = 0; }
            itemAtualNaFila = indiceNaFila;
        }

        pedirFocoAudio();

        playerFundo = new MediaPlayer();
        playerFundo.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        playerFundo.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        /* Fica mudo até assumir: enquanto o WebView ainda tem o áudio, o nativo
           já vem preparado no ponto certo — sem isso havia um silêncio (corte)
           do tamanho do prepare + seek. */
        playerFundo.setVolume(0f, 0f);

        playerFundo.setOnPreparedListener(mp -> {
            playerPronto = true;
            duracaoMs = mp.getDuration();
            /* O app pode ter voltado à tela entre o prepareAsync e este callback:
               aí NÃO pode iniciar — senão o áudio toca com o vídeo parado. */
            if (!deveEstarEmFundo) {
                Log.i(TAG, "fundo: app já voltou — não inicia");
                pararPlayerFundo();
                return;
            }
            /* Native já pré-carregado no ponto certo: não busca aqui (a busca
               acontece no assumir, com a posição exata que o WebView entregou). */
            if (cedeAguardando) {
                Log.i(TAG, "fundo: pronto — pedindo a vez ao WebView");
                handler.removeCallbacks(timeoutCede);
                handler.postDelayed(timeoutCede, CEDE_TIMEOUT_MS);
                MainActivity.chamarJs("window.nxCederParaNativo&&window.nxCederParaNativo("
                        + Math.max(0, ultPosMs) + ")");
                return;
            }
            if (ultPosMs > 0) mp.seekTo((int) Math.min(ultPosMs, Integer.MAX_VALUE));
            assumir();
        });

        playerFundo.setOnCompletionListener(mp -> {
            tocando = false;
            sessao.setPlaybackState(estado(false));
            // Avança na fila
            if (itemAtualNaFila >= 0 && itemAtualNaFila < itensNaFila - 1) {
                itemAtualNaFila++;
                try {
                    String json = filaJson;
                    org.json.JSONArray arr = new org.json.JSONArray(json);
                    boolean encontrou = false;
                    for (int i = itemAtualNaFila; i < arr.length(); i++) {
                        org.json.JSONObject obj = arr.getJSONObject(i);
                        String tipo = obj.optString("tipo", "");
                        String id = obj.optString("id", "");
                        String url = obj.optString("url", "");
                        if (i == itemAtualNaFila) {
                            ultFonteTipo = tipo; ultFonteId = id; ultFonteUrl = url;
                            ultTitulo = obj.optString("titulo", "");
                            ultSub = obj.optString("sub", "");
                            ultPosMs = (long) (obj.optDouble("pos", 0) * 1000);
                            ultDurMs = (long) (obj.optDouble("dur", 0) * 1000);
                            titulo = ultTitulo; sub = ultSub; posicaoMs = 0;
                            if (sessao != null) {
                                sessao.setMetadata(new MediaMetadata.Builder()
                                        .putString(MediaMetadata.METADATA_KEY_TITLE, titulo.isEmpty() ? "NEXUS VIDEO" : titulo)
                                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "NEXUS VIDEO")
                                        .putString(MediaMetadata.METADATA_KEY_ALBUM, "NEXUS VIDEO")
                                        .putLong(MediaMetadata.METADATA_KEY_DURATION, ultDurMs).build());
                            }
                            // descarta e recria
                            try { mp.stop(); mp.release(); } catch (Exception ignored) {}
                            playerFundo = null; playerPronto = false;
                            handler.postDelayed(() -> { if (itemAtualNaFila >= 0) iniciarPlayerFundo(); }, 200);
                            encontrou = true;
                            break;
                        }
                    }
                    if (!encontrou) Log.i(TAG, "fila: acabou");
                } catch (Exception e) {
                    Log.w(TAG, "fila: " + e);
                }
            } else {
                Log.i(TAG, "player fundo: fim sem fila");
            }
        });

        playerFundo.setOnSeekCompleteListener(mp -> {
            if (aguardandoSeekAssumir) iniciarAudioFundo(mp.getCurrentPosition());
        });

        playerFundo.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "erro player fundo: " + what + "/" + extra);
            tocando = false;
            sessao.setPlaybackState(estado(false));
            return true;
        });

        try {
            if ("biblioteca".equals(ultFonteTipo) && biblioteca != null) {
                playerFundo.setDataSource(getApplicationContext(), biblioteca.uriPublicaVideo(ultFonteId));
            } else if (!ultFonteUrl.isEmpty()) {
                playerFundo.setDataSource(ultFonteUrl);
            } else { playerFundo = null; return; }
            playerFundo.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "setDataSource: " + e);
            playerFundo = null;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private void pararPlayerFundo() {
        cedeAguardando = false;
        handler.removeCallbacks(timeoutCede);
        if (playerFundo == null) return;
        try { playerFundo.stop(); playerFundo.release(); } catch (Exception ignored) {}
        playerFundo = null; playerPronto = false; tocando = false;
        retornoAguardando = false;
        handler.removeCallbacks(timeoutRetorno);
        abandonarFocoAudio();
        Log.i(TAG, "player fundo parado");
    }

    /** O WebView confirmou: libera o nativo sem interromper o áudio. */
    private void confirmarRetorno() {
        retornoAguardando = false;
        handler.removeCallbacks(timeoutRetorno);
        pararPlayerFundo();
    }

    private void cmd(String acao) {
        if (playerFundo != null && playerPronto) {
            try {
                if ("play".equals(acao)) { if (!playerFundo.isPlaying()) playerFundo.start(); tocando = true; }
                else if ("pause".equals(acao)) { if (playerFundo.isPlaying()) playerFundo.pause(); tocando = false; }
                else if ("toggle".equals(acao)) {
                    if (playerFundo.isPlaying()) { playerFundo.pause(); tocando = false; }
                    else { playerFundo.start(); tocando = true; }
                }
                sessao.setPlaybackState(estado(tocando));
            } catch (Exception ignored) {}
            return;
        }
        // Manda para o WebView se não tiver nativo
        String js = "window.nexusControle&&window.nexusControle('" + acao + "')";
        MainActivity.chamarJs(js);
    }

    private void avançarFila() {
        if (playerFundo != null && playerPronto && itensNaFila > 0 && itemAtualNaFila < itensNaFila - 1) {
            itemAtualNaFila++;
            try {
                org.json.JSONArray arr = new org.json.JSONArray(filaJson);
                if (itemAtualNaFila < arr.length()) {
                    org.json.JSONObject obj = arr.getJSONObject(itemAtualNaFila);
                    ultFonteTipo = obj.optString("tipo", "");
                    ultFonteId = obj.optString("id", "");
                    ultFonteUrl = obj.optString("url", "");
                    ultTitulo = obj.optString("titulo", "");
                    ultPosMs = 0;
                    pararPlayerFundo();
                    handler.postDelayed(() -> iniciarPlayerFundo(), 200);
                }
            } catch (Exception ignored) {}
        } else {
            MainActivity.chamarJs("window.nexusControle&&window.nexusControle('next')");
        }
    }

    // -------------------------------------------------------------- foco áudio

    private void pedirFocoAudio() {
        if (audioMgr == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder b = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(f -> {
                        if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                            if (playerFundo != null && playerFundo.isPlaying()) {
                                playerFundo.setVolume(0.2f, 0.2f);
                            }
                        } else if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            retomarAposFocoTransient = playerFundo != null
                                    && (playerFundo.isPlaying() || tocando);
                            if (playerFundo != null && playerFundo.isPlaying()) playerFundo.pause();
                            if (retomarAposFocoTransient) tocando = true;
                            sessao.setPlaybackState(estado(retomarAposFocoTransient));
                        } else if (f == AudioManager.AUDIOFOCUS_LOSS) {
                            retomarAposFocoTransient = false;
                            if (playerFundo != null && playerFundo.isPlaying()) playerFundo.pause();
                            tocando = false;
                            sessao.setPlaybackState(estado(false));
                        } else if (f == AudioManager.AUDIOFOCUS_GAIN && playerFundo != null && playerPronto) {
                            if (retomarAposFocoTransient && deveEstarEmFundo) {
                                try {
                                    playerFundo.setVolume(1f, 1f);
                                    if (!playerFundo.isPlaying()) playerFundo.start();
                                    tocando = true;
                                    retomarAposFocoTransient = false;
                                    sessao.setPlaybackState(estado(true));
                                    Log.i(TAG, "audio focus ganho: retomada apos perda transitoria");
                                } catch (Exception e) {
                                    Log.w(TAG, "retomada apos ganho de foco: " + e);
                                }
                            } else if (playerFundo.isPlaying()) {
                                playerFundo.setVolume(1f, 1f);
                            }
                        }
                    });
            pedidoFoco = b.build();
            int resultado = audioMgr.requestAudioFocus(pedidoFoco);
            Log.i(TAG, "requestAudioFocus resultado=" + resultado);
        } else {
            int resultado = audioMgr.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            retomarAposFocoTransient = false;
            Log.i(TAG, "requestAudioFocus legado resultado=" + resultado);
        }
    }

    private void abandonarFocoAudio() {
        if (audioMgr == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pedidoFoco != null) audioMgr.abandonAudioFocusRequest(pedidoFoco);
            else audioMgr.abandonAudioFocus(null);
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------- estado

    private void aplicar(String novoTitulo, String novoSub, boolean estaTocando, long pos, long dur) {
        titulo = novoTitulo == null ? "" : novoTitulo;
        sub = novoSub == null ? "" : novoSub;
        tocando = estaTocando;
        posicaoMs = Math.max(0, pos);
        duracaoMs = Math.max(0, dur);
        if (sessao != null) {
            sessao.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, titulo.isEmpty() ? "NEXUS VIDEO" : titulo)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, sub.isEmpty() ? "NEXUS VIDEO" : sub)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "NEXUS VIDEO")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duracaoMs).build());
            sessao.setPlaybackState(estado(estaTocando));
        }
        refrescar();
    }

    private PlaybackState estado(boolean tocando) {
        long pos = playerFundo != null && playerPronto ? playerFundo.getCurrentPosition() : posicaoMs;
        return new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(tocando ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, 1.0f).build();
    }

    // -------------------------------------------------------------- loop

    private void agendar() { relogio.postDelayed(tique, appVisivel ? INTERVALO_MS : INTERVALO_FUNDO_MS); }
    private void reagendar() { relogio.removeCallbacks(tique); relogio.postDelayed(tique, INTERVALO_MS); }

    private void passo() {
        // Atualiza posição do player nativo
        if (playerFundo != null && playerPronto) {
            if (playerFundo.isPlaying()) tocando = true;
            posicaoMs = playerFundo.getCurrentPosition();
            duracaoMs = playerFundo.getDuration();
        }
        if (sessao != null && playerFundo != null) sessao.setPlaybackState(estado(tocando));

        boolean baixando = baixador != null && baixador.ativo();
        if (baixando) {
            String t = baixador.tituloAtual();
            if (t != null && !t.isEmpty()) ultimoTituloDownload = t;
            downloadEstavaAtivo = true;
        } else if (downloadEstavaAtivo) {
            downloadEstavaAtivo = false;
            notificacaoFinalDownload();
        }

        if ((!temMidia() && !baixando && playerFundo == null) || (!temMidia() && playerFundo == null)) {
            if (ociosoDesde == 0) ociosoDesde = System.currentTimeMillis();
            if (System.currentTimeMillis() - ociosoDesde >= OCIOSO_MS) {
                encerrarPorOciosidade(); return;
            }
        } else { ociosoDesde = 0; }
        refrescar();
    }

    private boolean temMidia() { return !titulo.isEmpty() || playerFundo != null; }

    // -------------------------------------------------------------- foreground

    private void refrescar() {
        boolean baixando = baixador != null && baixador.ativo();
        boolean midia = temMidia();
        if (midia || baixando) {
            int tipo = 0;
            if (midia) tipo |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (baixando) tipo |= ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (midia) {
                subirForeground(ID_PLAYER, notifPlayer(), tipo);
                if (baixando) gerenciador().notify(ID_DOWNLOAD, notifDownload());
                else gerenciador().cancel(ID_DOWNLOAD);
            } else {
                subirForeground(ID_DOWNLOAD, notifDownload(), tipo);
                gerenciador().cancel(ID_PLAYER);
            }
            precisaForeground = false; return;
        }
        if (precisaForeground) {
            subirForeground(ID_NEUTRA, notifNeutra(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            precisaForeground = false;
        }
        sairForeground(false);
        gerenciador().cancel(ID_PLAYER); gerenciador().cancel(ID_DOWNLOAD); gerenciador().cancel(ID_NEUTRA);
    }

    private void subirForeground(int id, Notification n, int tipo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && tipo != 0) startForeground(id, n, tipo);
            else startForeground(id, n);
            emForeground = true;
        } catch (Exception e) { Log.w(TAG, "startForeground: " + e); }
    }

    private void sairForeground(boolean remover) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(remover ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
            else stopForeground(remover);
        } catch (Exception ignored) {}
        emForeground = false;
    }

    private void encerrarPorOciosidade() {
        relogio.removeCallbacks(tique);
        pararPlayerFundo();
        sairForeground(false);
        gerenciador().cancel(ID_PLAYER);
        sessao.setActive(false);
        stopSelf();
        Log.i(TAG, "ocioso: servico liberado");
    }

    // -------------------------------------------------------------- notificações

    private void criarCanais() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel p = new NotificationChannel(CANAL_PLAYER, "Reprodução", NotificationManager.IMPORTANCE_LOW);
        p.setShowBadge(false); gerenciador().createNotificationChannel(p);
        NotificationChannel d = new NotificationChannel(CANAL_DOWNLOAD, "Downloads", NotificationManager.IMPORTANCE_LOW);
        d.setShowBadge(false); gerenciador().createNotificationChannel(d);
    }

    private NotificationManager gerenciador() { return (NotificationManager) getSystemService(NOTIFICATION_SERVICE); }

    private Notification.Builder construtor(String canal) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, canal) : new Notification.Builder(this);
    }

    private PendingIntent pendente(String acao) {
        Intent i = new Intent(this, PlayerService.class).setAction(acao);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, acao.hashCode(), i, f);
    }

    private PendingIntent abrirApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, i, f);
    }

    private Notification notifPlayer() {
        Notification.Action a = new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Anterior", pendente(ACAO_ANTERIOR)).build();
        Notification.Action b = new Notification.Action.Builder(tocando ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, tocando ? "Pausar" : "Tocar", pendente(ACAO_TOGGLE)).build();
        Notification.Action c = new Notification.Action.Builder(android.R.drawable.ic_media_next, "Próximo", pendente(ACAO_PROXIMA)).build();
        Notification.MediaStyle estilo = new Notification.MediaStyle().setMediaSession(sessao.getSessionToken()).setShowActionsInCompactView(0, 1, 2);
        return construtor(CANAL_PLAYER)
                .setContentTitle(titulo.isEmpty() ? "NEXUS VIDEO" : titulo)
                .setContentText(sub.isEmpty() ? "NEXUS VIDEO" : sub)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(abrirApp()).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(tocando || playerFundo != null).setOnlyAlertOnce(true)
                .addAction(a).addAction(b).addAction(c).setStyle(estilo).build();
    }

    private Notification notifDownload() {
        String nome = baixador.tituloAtual(); if (nome == null || nome.isEmpty()) nome = "vídeo do YouTube";
        int pct = Math.max(0, Math.min(100, (int) Math.round(baixador.percentualAtual())));
        String etapa = baixador.etapaAtual(); if (etapa == null || etapa.isEmpty()) etapa = baixador.faseAtual();
        String q = baixador.qualidadeAtual(); if (q != null && !q.isEmpty()) etapa = q + " · " + etapa;
        Notification.Action cancel = new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", pendente(ACAO_CANCELAR)).build();
        return construtor(CANAL_DOWNLOAD)
                .setContentTitle("Baixando: " + nome).setContentText(pct + "% · " + etapa)
                .setSmallIcon(android.R.drawable.stat_sys_download).setProgress(100, pct, false)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(abrirApp()).addAction(cancel).build();
    }

    private Notification notifNeutra() {
        return construtor(CANAL_PLAYER).setContentTitle("NEXUS VIDEO").setContentText("pronto")
                .setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(abrirApp()).setOnlyAlertOnce(true).build();
    }

    private void notificacaoFinalDownload() {
        if (baixador == null) return;
        String fase = baixador.faseAtual();
        String erro = baixador.erroAtual();
        boolean falhou = "erro".equals(fase) || "cancelado".equals(fase) || (erro != null && !erro.isEmpty());
        String nome = ultimoTituloDownload.isEmpty() ? "vídeo" : ultimoTituloDownload;
        Notification aviso = construtor(CANAL_DOWNLOAD)
                .setContentTitle(falhou ? "✖ Download não concluído" : "✓ Vídeo salvo")
                .setContentText(falhou ? (erro == null || erro.isEmpty() ? "falha no download" : erro) : nome + " · Movies/NexusVideo")
                .setSmallIcon(falhou ? android.R.drawable.stat_notify_error : android.R.drawable.stat_sys_download_done)
                .setOngoing(false).setAutoCancel(true).setContentIntent(abrirApp()).build();
        gerenciador().notify(ID_DOWNLOAD, aviso);
    }

    private static void disparar(Context ctx, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent);
            else ctx.startService(intent);
        } catch (Exception e) { Log.w(TAG, "disparar: " + e); }
    }
}