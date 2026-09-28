package com.leo.nexusvideo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * NEXUS VIDEO em segundo plano e na tela bloqueada.
 *
 * <p>── Por que existe ────────────────────────────────────────────────────<br>
 * O vídeo toca dentro do {@code WebView}, e o WebView <b>não publica</b> a
 * MediaSession dele para o Android (o {@code navigator.mediaSession} do
 * JavaScript não sai do WebView). Sem uma sessão nativa, o sistema não sabe que
 * há vídeo rodando: nada aparece na tela bloqueada, os botões do fone não fazem
 * nada e o processo é o primeiro candidato a ser encerrado em segundo plano.</p>
 *
 * <p>Este serviço resolve os dois lados:</p>
 * <ul>
 *   <li>mantém o processo vivo em primeiro plano — tipo {@code mediaPlayback}
 *       enquanto há vídeo carregado e {@code dataSync} enquanto há download;</li>
 *   <li>publica a MediaSession (metadados, estado, posição) e as notificações —
 *       a de mídia com os controles e a de download com a barra de progresso.</li>
 * </ul>
 *
 * <p>O fluxo é de mão dupla:</p>
 * <ul>
 *   <li>interface → nativo: {@code AndroidApp.tocando(titulo, sub, tocando, pos, dur)}</li>
 *   <li>nativo → interface: {@code webView.evaluateJavascript("nexusControle('toggle')")}</li>
 * </ul>
 */
public class PlayerService extends Service {

    private static final String TAG = "NexusVideoPlay";

    private static final String CANAL_PLAYER = "nexus_video_player";
    private static final String CANAL_DOWNLOAD = "nexus_video_download";
    private static final int ID_PLAYER = 8577;
    private static final int ID_DOWNLOAD = 8578;
    private static final int ID_NEUTRA = 8579;

    /** Com o app na tela o relógio bate de perto (a barra do download acompanha). */
    private static final long INTERVALO_MS = 1000L;
    /** Fora da tela: o recurso de mídia anda sozinho, não precisa acordar tanto. */
    private static final long INTERVALO_FUNDO_MS = 5000L;
    /** Sem nada ativo por este tempo: o serviço se libera. */
    private static final long OCIOSO_MS = 15000L;

    /** Ações da notificação (também usadas como ações de Intent). */
    public static final String ACAO_TOGGLE = "com.leo.nexusvideo.TOGGLE";
    public static final String ACAO_PROXIMA = "com.leo.nexusvideo.PROXIMA";
    public static final String ACAO_ANTERIOR = "com.leo.nexusvideo.ANTERIOR";
    public static final String ACAO_CANCELAR = "com.leo.nexusvideo.CANCELAR";

    /** Estado do player, entregue por Intent (vale já na 1ª chamada). */
    private static final String EXTRA_TITULO = "nexus.titulo";
    private static final String EXTRA_SUB = "nexus.sub";
    private static final String EXTRA_TOCANDO = "nexus.tocando";
    private static final String EXTRA_POS = "nexus.posicao";
    private static final String EXTRA_DUR = "nexus.duracao";

    /** Registrado pela MainActivity: é quem sabe do download em andamento. */
    private static volatile YtVideoDownload baixador;

    /**
     * O app está na tela? Com o app visível o relógio bate a cada segundo; fora
     * da tela ele espaça, porque o avanço da posição vem do recurso de mídia do
     * sistema e não de acordar a CPU a cada segundo.
     */
    private static volatile boolean appVisivel;

    /** Instância viva, para o relógio poder ser reagendado de fora. */
    private static volatile PlayerService instancia;

    private MediaSession sessao;
    private final Handler relogio = new Handler(Looper.getMainLooper());

    private String titulo = "";
    private String sub = "";
    private boolean tocando;
    private long posicaoMs;
    private long duracaoMs;

    private boolean emForeground;
    /** O Android exige que um startForegroundService vire primeiro plano. */
    private boolean precisaForeground;
    private long ociosoDesde;
    private boolean downloadEstavaAtivo;
    private String ultimoTituloDownload = "";

    private final Runnable tique = new Runnable() {
        @Override
        public void run() {
            passo();
            if (relogio != null) agendar();
        }
    };

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void onCreate() {
        super.onCreate();
        instancia = this;
        criarCanais();

        sessao = new MediaSession(this, "NEXUS VIDEO");
        sessao.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);

        /* É aqui que chegam os botões do fone, os controles da tela bloqueada e
           a barra de progresso arrastável. Cada ação vira uma chamada ao JS. */
        sessao.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { avisarInterface("play"); }
            @Override public void onPause() { avisarInterface("pause"); }
            @Override public void onStop() { avisarInterface("pause"); }
            @Override public void onSkipToNext() { avisarInterface("next"); }
            @Override public void onSkipToPrevious() { avisarInterface("prev"); }
            @Override public void onSeekTo(long alvo) {
                MainActivity.chamarJs("window.nexusControle&&window.nexusControle('seek',"
                        + alvo + ")");
            }
        });
        sessao.setActive(true);
        sessao.setPlaybackState(montarEstado(false));
        Log.i(TAG, "MediaSession ativa");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        precisaForeground = true;

        String acao = intent == null ? null : intent.getAction();

        if (ACAO_TOGGLE.equals(acao)) avisarInterface("toggle");
        else if (ACAO_PROXIMA.equals(acao)) avisarInterface("next");
        else if (ACAO_ANTERIOR.equals(acao)) avisarInterface("prev");
        else if (ACAO_CANCELAR.equals(acao) && baixador != null) baixador.cancelar();

        if (intent != null && intent.hasExtra(EXTRA_TOCANDO)) {
            aplicar(intent.getStringExtra(EXTRA_TITULO),
                    intent.getStringExtra(EXTRA_SUB),
                    intent.getBooleanExtra(EXTRA_TOCANDO, false),
                    intent.getLongExtra(EXTRA_POS, 0L),
                    intent.getLongExtra(EXTRA_DUR, 0L));
        } else {
            refrescar();
        }

        relogio.removeCallbacks(tique);
        agendar();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        relogio.removeCallbacks(tique);
        instancia = null;
        sairDoForeground(true);
        if (sessao != null) {
            sessao.setActive(false);
            sessao.release();
            sessao = null;
        }
        super.onDestroy();
        Log.i(TAG, "servico encerrado");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ ponte estática

    /** A MainActivity informa quem sabe do download (para a notificação de progresso). */
    public static void registrar(YtVideoDownload b) {
        baixador = b;
    }

    /** Botão do fone/Bluetooth: repassa a ação para a interface viva. */
    public static void peloFone(Context ctx, String acao) {
        PlayerService s = instancia;
        if (s != null) s.avisarInterface(acao);
    }

    /** Vídeo carregado e tocando. */
    public static void tocando(Context ctx, String titulo, String sub,
                               long posicaoMs, long duracaoMs) {
        enviar(ctx, true, titulo, sub, posicaoMs, duracaoMs);
    }

    /** Vídeo carregado e pausado (o app segue pronto para retomar). */
    public static void pausado(Context ctx, String titulo, String sub,
                               long posicaoMs, long duracaoMs) {
        enviar(ctx, false, titulo, sub, posicaoMs, duracaoMs);
    }

    /** Nada carregado no visor: sai da tela bloqueada. */
    public static void parado(Context ctx) {
        enviar(ctx, false, "", "", 0L, 0L);
    }

    /** O app voltou para a tela: o relógio volta ao ritmo normal. */
    public static void appEmPrimeiroPlano() {
        appVisivel = true;
        PlayerService s = instancia;
        if (s != null) s.relogio.post(s::reagendar);
    }

    /** O app saiu da tela (minimizado): o relógio pode espaçar os passos. */
    public static void appEmSegundoPlano() {
        appVisivel = false;
    }

    /** Pede para encerrar o serviço (o app inteiro foi fechado). */
    public static void encerrar(Context ctx) {
        ctx.stopService(new Intent(ctx, PlayerService.class));
    }

    private static void enviar(Context ctx, boolean estaTocando, String titulo, String sub,
                               long posicaoMs, long duracaoMs) {
        Intent intent = new Intent(ctx, PlayerService.class);
        intent.putExtra(EXTRA_TOCANDO, estaTocando);
        intent.putExtra(EXTRA_TITULO, titulo == null ? "" : titulo);
        intent.putExtra(EXTRA_SUB, sub == null ? "" : sub);
        intent.putExtra(EXTRA_POS, posicaoMs);
        intent.putExtra(EXTRA_DUR, duracaoMs);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "nao consegui avisar o servico: " + e);
        }
    }

    // ------------------------------------------------------------------ estado

    private void aplicar(String novoTitulo, String novoSub, boolean estaTocando,
                         long pos, long dur) {
        this.titulo = novoTitulo == null ? "" : novoTitulo;
        this.sub = novoSub == null ? "" : novoSub;
        this.tocando = estaTocando;
        this.posicaoMs = Math.max(0L, pos);
        this.duracaoMs = Math.max(0L, dur);

        if (sessao != null) {
            sessao.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE,
                            titulo.isEmpty() ? "NEXUS VIDEO" : titulo)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST,
                            sub.isEmpty() ? "NEXUS VIDEO" : sub)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "NEXUS VIDEO")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duracaoMs)
                    .build());
            sessao.setPlaybackState(montarEstado(estaTocando));
        }

        refrescar();
    }

    private PlaybackState montarEstado(boolean estaTocando) {
        return new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_STOP)
                .setState(estaTocando ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        posicaoMs, 1.0f)
                .build();
    }

    // ------------------------------------------------------------------ relógio

    /** Próximo passo: de perto com o app na tela, espaçado em segundo plano. */
    private void agendar() {
        relogio.postDelayed(tique, appVisivel ? INTERVALO_MS : INTERVALO_FUNDO_MS);
    }

    /** Reagenda com o intervalo do momento (usado ao voltar para a tela). */
    private void reagendar() {
        relogio.removeCallbacks(tique);
        relogio.postDelayed(tique, INTERVALO_MS);
    }

    /**
     * Com o app fora da tela o WebView é congelado e ninguém mais informa a
     * posição. A suposição é deliberada: tocando, o vídeo anda no relógio
     * normal; posição e duração reais voltam assim que a interface responde.
     */
    private void passarAoVivo(long intervalo) {
        if (!tocando || duracaoMs <= 0) return;
        posicaoMs = Math.min(duracaoMs, posicaoMs + intervalo);
        if (sessao != null) sessao.setPlaybackState(montarEstado(true));
    }

    /**
     * O download é 100% nativo (o servidor local é do processo), então o
     * progresso da notificação é lido direto do baixador — sem depender do
     * WebView estar vivo ou visível.
     */
    private void passo() {
        passarAoVivo(appVisivel ? INTERVALO_MS : INTERVALO_FUNDO_MS);

        boolean baixando = baixador != null && baixador.ativo();

        if (baixando) {
            String t = baixador.tituloAtual();
            if (t != null && !t.isEmpty()) ultimoTituloDownload = t;
            downloadEstavaAtivo = true;
        } else if (downloadEstavaAtivo) {
            downloadEstavaAtivo = false;
            notificacaoFinalDownload();
        }

        if (!temMidia() && !baixando) {
            if (ociosoDesde == 0) ociosoDesde = System.currentTimeMillis();
            if (System.currentTimeMillis() - ociosoDesde >= OCIOSO_MS) {
                encerrarPorOciosidade();
                return;
            }
        } else {
            ociosoDesde = 0;
        }

        refrescar();
    }

    /** Há vídeo carregado no visor (mesmo pausado)? */
    private boolean temMidia() {
        return !titulo.isEmpty();
    }

    // ------------------------------------------------------------------ primeiro plano

    private void refrescar() {
        boolean baixando = baixador != null && baixador.ativo();
        boolean midia = temMidia();

        if (midia || baixando) {
            int tipo = 0;
            if (midia) tipo |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (baixando) tipo |= ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

            if (midia) {
                subirPrimeiroPlano(ID_PLAYER, notificacaoPlayer(), tipo);
                if (baixando) gerenciador().notify(ID_DOWNLOAD, notificacaoDownload());
                else gerenciador().cancel(ID_DOWNLOAD);
            } else {
                subirPrimeiroPlano(ID_DOWNLOAD, notificacaoDownload(), tipo);
                gerenciador().cancel(ID_PLAYER);
            }
            precisaForeground = false;
            return;
        }

        /* Nada ativo. Se o serviço foi acordado com startForegroundService, é
           obrigatório virar primeiro plano por um instante — senão o Android
           derruba o processo com ForegroundServiceDidNotStartInTimeException.
           Em seguida a notificação sai e o serviço fica livre. */
        if (precisaForeground) {
            subirPrimeiroPlano(ID_NEUTRA, notificacaoNeutra(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            precisaForeground = false;
        }
        sairDoForeground(false);
        gerenciador().cancel(ID_PLAYER);
        gerenciador().cancel(ID_DOWNLOAD);
        gerenciador().cancel(ID_NEUTRA);
    }

    private void subirPrimeiroPlano(int id, Notification notificacao, int tipo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && tipo != 0) {
                startForeground(id, notificacao, tipo);
            } else {
                startForeground(id, notificacao);
            }
            emForeground = true;
        } catch (Exception e) {
            Log.w(TAG, "startForeground: " + e);
        }
    }

    private void sairDoForeground(boolean remover) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(remover ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(remover);
            }
        } catch (Exception ignored) { }
        emForeground = false;
    }

    private void encerrarPorOciosidade() {
        relogio.removeCallbacks(tique);
        sairDoForeground(false);          /* mantém o aviso de "vídeo salvo" */
        gerenciador().cancel(ID_PLAYER);
        sessao.setActive(false);
        stopSelf();
        Log.i(TAG, "sem nada ativo: servico liberado");
    }

    // ------------------------------------------------------------------ notificações

    private void criarCanais() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel player = new NotificationChannel(CANAL_PLAYER,
                "Reprodução", NotificationManager.IMPORTANCE_LOW);
        player.setDescription("Controles do vídeo que está tocando");
        player.setShowBadge(false);
        gerenciador().createNotificationChannel(player);

        NotificationChannel download = new NotificationChannel(CANAL_DOWNLOAD,
                "Downloads", NotificationManager.IMPORTANCE_LOW);
        download.setDescription("Progresso dos vídeos baixados do YouTube");
        download.setShowBadge(false);
        gerenciador().createNotificationChannel(download);
    }

    private NotificationManager gerenciador() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private Notification.Builder construtor(String canal) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, canal)
                : new Notification.Builder(this);
    }

    private PendingIntent pendente(String acao) {
        Intent intent = new Intent(this, PlayerService.class).setAction(acao);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, acao.hashCode(), intent, flags);
    }

    private PendingIntent abrirApp() {
        Intent abrir = new Intent(this, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, abrir, flags);
    }

    private Notification notificacaoPlayer() {
        Notification.Action anterior = new Notification.Action.Builder(
                android.R.drawable.ic_media_previous, "Anterior",
                pendente(ACAO_ANTERIOR)).build();
        Notification.Action alternar = new Notification.Action.Builder(
                tocando ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                tocando ? "Pausar" : "Tocar", pendente(ACAO_TOGGLE)).build();
        Notification.Action proxima = new Notification.Action.Builder(
                android.R.drawable.ic_media_next, "Próximo",
                pendente(ACAO_PROXIMA)).build();

        Notification.MediaStyle estilo = new Notification.MediaStyle()
                .setMediaSession(sessao.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2);

        return construtor(CANAL_PLAYER)
                .setContentTitle(titulo.isEmpty() ? "NEXUS VIDEO" : titulo)
                .setContentText(sub.isEmpty() ? "NEXUS VIDEO" : sub)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(abrirApp())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(tocando)
                .setOnlyAlertOnce(true)
                .addAction(anterior)
                .addAction(alternar)
                .addAction(proxima)
                .setStyle(estilo)
                .build();
    }

    private Notification notificacaoDownload() {
        String nome = baixador.tituloAtual();
        if (nome == null || nome.isEmpty()) nome = "vídeo do YouTube";
        int pct = (int) Math.round(baixador.percentualAtual());
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;

        String etapa = baixador.etapaAtual();
        if (etapa == null || etapa.isEmpty()) etapa = baixador.faseAtual();
        String qualidade = baixador.qualidadeAtual();
        if (qualidade != null && !qualidade.isEmpty()) etapa = qualidade + " · " + etapa;

        Notification.Action cancelar = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Cancelar",
                pendente(ACAO_CANCELAR)).build();

        return construtor(CANAL_DOWNLOAD)
                .setContentTitle("Baixando: " + nome)
                .setContentText(pct + "% · " + etapa)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, pct, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(abrirApp())
                .addAction(cancelar)
                .build();
    }

    /** Só existe para cumprir o contrato de primeiro plano; sai em seguida. */
    private Notification notificacaoNeutra() {
        return construtor(CANAL_PLAYER)
                .setContentTitle("NEXUS VIDEO")
                .setContentText("pronto")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(abrirApp())
                .setOnlyAlertOnce(true)
                .build();
    }

    private void notificacaoFinalDownload() {
        if (baixador == null) return;

        String fase = baixador.faseAtual();
        String erro = baixador.erroAtual();
        boolean falhou = "erro".equals(fase) || "cancelado".equals(fase)
                || (erro != null && !erro.isEmpty());

        String nome = ultimoTituloDownload.isEmpty() ? "vídeo" : ultimoTituloDownload;

        Notification aviso = construtor(CANAL_DOWNLOAD)
                .setContentTitle(falhou ? "✖ Download não concluído" : "✓ Vídeo salvo")
                .setContentText(falhou
                        ? (erro == null || erro.isEmpty() ? "falha no download" : erro)
                        : nome + " · Movies/NexusVideo")
                .setSmallIcon(falhou ? android.R.drawable.stat_notify_error
                        : android.R.drawable.stat_sys_download_done)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(abrirApp())
                .build();

        gerenciador().notify(ID_DOWNLOAD, aviso);
    }

    // ------------------------------------------------------------------ nativo → interface

    private void avisarInterface(String acao) {
        Log.i(TAG, "controle do sistema: " + acao);
        MainActivity.chamarJs("window.nexusControle&&window.nexusControle('" + acao + "')");
    }
}
