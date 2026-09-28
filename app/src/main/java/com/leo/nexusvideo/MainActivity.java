package com.leo.nexusvideo;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

/**
 * NEXUS VIDEO — atividade principal.
 *
 * <p>O app é um WebView que carrega a interface de um servidor HTTP local
 * (127.0.0.1). É esse servidor que serve os vídeos da biblioteca e faz a ponte
 * com o download do YouTube — o WebView nunca fala com a internet direto.</p>
 *
 * <p>Segundo plano e tela bloqueada ficam por conta do {@link PlayerService}:
 * ele mantém o processo em primeiro plano (playback ou download) e publica a
 * MediaSession + as notificações que o WebView não consegue publicar sozinho.</p>
 */
public class MainActivity extends Activity {

    private static final String TAG = "NexusVideo";
    private static final int PEDIDO_PERMISSAO = 77;

    /** A instância viva: por ela o serviço manda comandos para o JavaScript. */
    private static WeakReference<MainActivity> ativa = new WeakReference<>(null);

    private WebView webView;
    private NexusServer servidor;
    private VideoLibrary biblioteca;
    private YtVideoDownload baixador;

    private View fullscreenView;
    private long ultimoVoltar = 0;

    private boolean jsPronto = false;
    private String jsPendente = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ativa = new WeakReference<>(this);

        biblioteca = new VideoLibrary(this);
        baixador = new YtVideoDownload(this, biblioteca);
        PlayerService.registrar(baixador);

        montarTelaCheia();

        try {
            servidor = new NexusServer(this, biblioteca, baixador);
            int porta = servidor.iniciar();
            Log.i(TAG, "porta local: " + porta);
            montarWebView("http://127.0.0.1:" + porta + "/");
        } catch (Exception e) {
            Log.e(TAG, "não consegui subir o servidor", e);
            final android.widget.TextView erro = new android.widget.TextView(this);
            erro.setText("Não foi possível iniciar o servidor local.\n" + e.getMessage());
            erro.setTextColor(0xFFFFFFFF);
            erro.setPadding(40, 40, 40, 40);
            setContentView(erro);
            return;
        }

        biblioteca.recarregar();
        pedirPermissoes();
    }

    private void montarTelaCheia() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ------------------------------------------------------------------ //
    //  WebView
    // ------------------------------------------------------------------ //

    private void montarWebView(String endereco) {
        webView = new WebView(this);

        WebSettings cfg = webView.getSettings();
        cfg.setJavaScriptEnabled(true);
        cfg.setDomStorageEnabled(true);
        cfg.setDatabaseEnabled(true);
        cfg.setMediaPlaybackRequiresUserGesture(false);
        cfg.setBuiltInZoomControls(false);
        cfg.setDisplayZoomControls(false);
        cfg.setLoadWithOverviewMode(true);
        cfg.setUseWideViewPort(true);
        cfg.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        cfg.setAllowFileAccess(false);            /* tudo vem do servidor local */
        cfg.setAllowContentAccess(true);

        webView.setBackgroundColor(0xFF000000);
        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setScrollbarFadingEnabled(true);

        /* Cara de APP, não de página: sem menu de "copiar/selecionar" ao segurar. */
        webView.setLongClickable(false);
        webView.setHapticFeedbackEnabled(false);
        webView.setOnLongClickListener(v -> true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                avisarPronto();
            }
        });

        /* Tela cheia do player de vídeo (o <video> pede fullscreen). */
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                webView.setVisibility(View.GONE);
                ((FrameLayout) findViewById(android.R.id.content)).addView(
                        fullscreenView, new FrameLayout.LayoutParams(-1, -1));
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }

            @Override
            public void onHideCustomView() {
                if (fullscreenView == null) return;
                ((FrameLayout) findViewById(android.R.id.content)).removeView(fullscreenView);
                fullscreenView = null;
                webView.setVisibility(View.VISIBLE);
                montarTelaCheia();
            }
        });

        webView.addJavascriptInterface(new PonteApp(), "AndroidApp");

        setContentView(webView);
        webView.loadUrl(endereco);
    }

    /** Avisa a interface que o app nativo terminou de carregar. */
    private void avisarPronto() {
        jsPronto = true;
        if (webView == null) return;
        webView.evaluateJavascript("window.nxPronto&&window.nxPronto()", null);
        enviarJsPendente();
    }

    /**
     * Reenvia o último comando recebido enquanto o WebView ainda não estava
     * pronto (ex.: o usuário pausou pela tela bloqueada antes da página carregar).
     */
    private void enviarJsPendente() {
        if (!jsPronto || jsPendente == null || webView == null) return;
        String comando = jsPendente;
        jsPendente = null;
        try {
            webView.evaluateJavascript(comando, null);
        } catch (Exception e) {
            jsPendente = comando;
            Log.w(TAG, "reenvio de controle: " + e);
        }
    }

    /** Executa JavaScript na interface. Usado pelo PlayerService. */
    public static void chamarJs(final String js) {
        final MainActivity instancia = ativa.get();
        if (instancia == null || instancia.webView == null) return;

        instancia.runOnUiThread(() -> {
            if (!instancia.jsPronto) {
                instancia.jsPendente = js;
                return;
            }
            try {
                instancia.webView.evaluateJavascript(js, null);
            } catch (Exception e) {
                instancia.jsPendente = js;
                Log.w(TAG, "chamarJs: " + e);
            }
        });
    }

    private void recarregarBiblioteca() {
        biblioteca.recarregar();
    }

    /** Ponte exposta ao JS como `AndroidApp`. */
    private class PonteApp {
        @android.webkit.JavascriptInterface
        public void recarregarBiblioteca() {
            MainActivity.this.runOnUiThread(MainActivity.this::recarregarBiblioteca);
        }

        /**
         * A interface informa o que está tocando. É isso que faz o vídeo
         * aparecer na tela bloqueada, na notificação e nos botões do fone.
         */
        @JavascriptInterface
        public void tocando(final String titulo, final String sub,
                            final boolean estaTocando, final long posicaoMs,
                            final long duracaoMs) {
            if (estaTocando) {
                PlayerService.tocando(MainActivity.this, titulo, sub, posicaoMs, duracaoMs);
            } else {
                PlayerService.pausado(MainActivity.this, titulo, sub, posicaoMs, duracaoMs);
            }
        }

        /** Nada carregado no visor: tira o vídeo da tela bloqueada. */
        @JavascriptInterface
        public void parado() {
            PlayerService.parado(MainActivity.this);
        }

        /** O usuário retomou o vídeo pela tela bloqueada/notificação. */
        @JavascriptInterface
        public void appEmPrimeiroPlano() {
            PlayerService.appEmPrimeiroPlano();
        }

        @android.webkit.JavascriptInterface
        public boolean copiarTextoPix(String texto) {
            if (!"e0147a61-18b6-4cf0-972e-74d3cf38c90a".equals(texto)) return false;
            try {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard == null) return false;
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "Chave Pix NEXUS VIDEO", texto));
                return true;
            } catch (Exception e) {
                Log.w(TAG, "não foi possível copiar a chave Pix", e);
                return false;
            }
        }

        @android.webkit.JavascriptInterface
        public String versao() {
            return "3.5";
        }

        @android.webkit.JavascriptInterface
        public void sair() {
            MainActivity.this.runOnUiThread(MainActivity.this::finish);
        }
    }

    // ------------------------------------------------------------------ //
    //  Permissões
    // ------------------------------------------------------------------ //

    private void pedirPermissoes() {
        java.util.List<String> faltando = new java.util.ArrayList<>();

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                faltando.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                faltando.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            faltando.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            faltando.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (!faltando.isEmpty()) {
            requestPermissions(faltando.toArray(new String[0]), PEDIDO_PERMISSAO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permissoes, int[] resultados) {
        super.onRequestPermissionsResult(codigo, permissoes, resultados);
        if (codigo != PEDIDO_PERMISSAO) return;

        for (int i = 0; i < permissoes.length; i++) {
            if (resultados[i] != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "permissão negada: " + permissoes[i]);
            }
        }
        biblioteca.recarregar();
        avisarPronto();
    }

    // ------------------------------------------------------------------ //
    //  Ciclo de vida
    // ------------------------------------------------------------------ //

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            webView.getSettings().setJavaScriptEnabled(true);
            webView.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null);
            return;
        }
        /* duplo toque em voltar fecha o app (o download continua no servidor) */
        long agora = System.currentTimeMillis();
        if (agora - ultimoVoltar < 2000) {
            super.onBackPressed();
            return;
        }
        ultimoVoltar = agora;
        if (webView != null) {
            webView.evaluateJavascript(
                    "window.nxAvisoVoltar&&window.nxAvisoVoltar()", null);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        /* Minimizado (home/gesto de recentes): o app não é encerrado e o
           PlayerService mantém o vídeo rodando — é isto que faz o vídeo
           continuar com a tela bloqueada. Nada de pausar o áudio aqui. */
        PlayerService.appEmSegundoPlano();
    }

    @Override
    protected void onStart() {
        super.onStart();
        PlayerService.appEmPrimeiroPlano();
    }

    @Override
    protected void onDestroy() {
        /* Fechando de verdade (voltar confirmado ou app removido dos recentes):
           para TODO o áudio do WebView antes de destruir — senão o HTML segue
           tocando, e o WebView ainda está vivo neste ponto. */
        if (isFinishing() && webView != null) {
            try {
                webView.evaluateJavascript(
                        "window.nxPausarTudo&&window.nxPausarTudo()", null);
            } catch (Exception e) {
                Log.w(TAG, "pausa final do audio: " + e);
            }
        }
        PlayerService.encerrar(this);
        super.onDestroy();
        if (servidor != null) servidor.parar();
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ //
    //  Abertura pelo "compartilhar" do YouTube
    // ------------------------------------------------------------------ //

    @Override
    protected void onResume() {
        super.onResume();
        PlayerService.appEmPrimeiroPlano();
        tentarLinkCompartilhado();
    }

    /* Foco de áudio é gerenciado pelo PRÓPRIO WebView (AudioFocusDelegate do
       Chromium): o <video> pede AUDIOFOCUS_GAIN ao tocar e suspende/retoma sozinho
       quando outro app toma o foco. Um listener do app recebia o LOSS gerado pelo
       próprio WebView e pausava a reprodução logo no primeiro play ("toca e para,
       só funciona no segundo clique"). Não pedir foco nem escutar perda aqui. */

    private void tentarLinkCompartilhado() {
        try {
            android.content.Intent intent = getIntent();
            if (intent == null || !android.content.Intent.ACTION_SEND.equals(intent.getAction())) {
                return;
            }
            String texto = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
            if (texto == null || texto.isEmpty()) return;
            intent.setAction(null);
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.nxLinkRecebido&&window.nxLinkRecebido("
                                + org.json.JSONObject.quote(texto) + ")", null);
            }
        } catch (Exception e) {
            Log.w(TAG, "link compartilhado: " + e.getMessage());
        }
    }
}
