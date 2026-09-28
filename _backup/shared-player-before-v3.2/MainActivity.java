package com.leo.nexusvideo;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.graphics.Matrix;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

public class MainActivity extends Activity {

    private static final String TAG = "NexusVideo";
    private static final int PEDIDO_PERMISSAO = 77;
    private static WeakReference<MainActivity> ativa = new WeakReference<>(null);
    private static VideoLibrary bibliotecaCompartilhada;
    private static YtVideoDownload baixadorCompartilhado;
    private static NexusServer servidorCompartilhado;

    private WebView webView;
    private FrameLayout telaRaiz;
    private TextureView videoTexture;
    private Surface videoSurface;
    private boolean surfaceVisivel;
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
        biblioteca = new VideoLibrary(getApplicationContext());
        baixador = new YtVideoDownload(getApplicationContext(), biblioteca);
        bibliotecaCompartilhada = biblioteca;
        baixadorCompartilhado = baixador;
        PlayerService.registrar(biblioteca, baixador);
        montarTelaCheia();
        try {
            servidor = new NexusServer(this, biblioteca, baixador);
            int porta = servidor.iniciar();
            servidorCompartilhado = servidor;
            Log.i(TAG, "porta local: " + porta);
            montarWebView("http://127.0.0.1:" + porta + "/");
        } catch (Exception e) {
            Log.e(TAG, "servidor", e);
            setContentView(new android.widget.TextView(this) {{
                setText("Erro: " + e.getMessage());
                setTextColor(0xFFFFFFFF); setPadding(40, 40, 40, 40);
            }});
            return;
        }
        biblioteca.recarregar();
        pedirPermissoes();
    }

    private void montarTelaCheia() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

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
        cfg.setAllowFileAccess(false);
        cfg.setAllowContentAccess(true);
        webView.setBackgroundColor(0xFF000000);
        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setScrollbarFadingEnabled(true);
        webView.setLongClickable(false);
        webView.setHapticFeedbackEnabled(false);
        webView.setOnLongClickListener(v -> true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                jsPronto = true;
                if (webView != null) webView.evaluateJavascript("window.nxPronto&&window.nxPronto()", null);
                enviarJsPendente();
            }
        });
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return; }
                fullscreenView = view;
                webView.setVisibility(View.GONE);
                ((FrameLayout) findViewById(android.R.id.content)).addView(fullscreenView, new FrameLayout.LayoutParams(-1, -1));
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            @Override
            public void onHideCustomView() {
                if (fullscreenView == null) return;
                ((FrameLayout) findViewById(android.R.id.content)).removeView(fullscreenView);
                fullscreenView = null; webView.setVisibility(View.VISIBLE); montarTelaCheia();
            }
        });
        webView.addJavascriptInterface(new PonteApp(), "AndroidApp");
        setContentView(webView);
        webView.loadUrl(endereco);
    }

    private void enviarJsPendente() {
        if (!jsPronto || jsPendente == null || webView == null) return;
        String cmd = jsPendente; jsPendente = null;
        try { webView.evaluateJavascript(cmd, null); } catch (Exception e) { jsPendente = cmd; }
    }

    public static void chamarJs(final String js) {
        final MainActivity inst = ativa.get();
        if (inst == null || inst.webView == null) return;
        inst.runOnUiThread(() -> {
            if (!inst.jsPronto) { inst.jsPendente = js; return; }
            try { inst.webView.evaluateJavascript(js, null); } catch (Exception e) { inst.jsPendente = js; }
        });
    }

    private class PonteApp {
        @android.webkit.JavascriptInterface
        public void recarregarBiblioteca() {
            MainActivity.this.runOnUiThread(() -> biblioteca.recarregar());
        }

        @JavascriptInterface
        public void pausaExplicita() {
            PlayerService.pausaExplicita();
        }

        @JavascriptInterface
        public void snapshotLifecycle(final String titulo, final String sub, final boolean tocando,
                                      final long posMs, final long durMs, final String tipo,
                                      final String id, final String url) {
            PlayerService.activityVaiPausar(titulo, sub, tocando, posMs, durMs, tipo, id, url);
        }

        @JavascriptInterface
        public void tocando(final String titulo, final String sub,
                            final boolean estaTocando, final long posMs, final long durMs,
                            final String tipo, final String id, final String url) {
            PlayerService.setFonte(MainActivity.this, tipo, id, url, titulo, sub, posMs, durMs, estaTocando);
        }

        @JavascriptInterface
        public void tocando(final String titulo, final String sub,
                            final boolean estaTocando, final long posMs, final long durMs) {
            tocando(titulo, sub, estaTocando, posMs, durMs, "", "", "");
        }

        @JavascriptInterface
        public void setFila(final String json, final int indice) {
            PlayerService.setFila(json, indice);
        }

        /** Captura fila e fonte no instante de saída da tela. */
        @JavascriptInterface
        public void sairDaTela(final String fonteJson, final String filaJson,
                               final int indice) {
            PlayerService.entrarEmSegundoPlano(MainActivity.this, fonteJson, filaJson, indice);
        }

        /** O <video> pausou no ponto exato: o nativo assume agora, sem corte. */
        @JavascriptInterface
        public void cedeuAgora(final long posMs) {
            PlayerService.cedeuAgora(MainActivity.this, posMs);
        }

        /** O <video> voltou a reproduzir: o nativo pode ser liberado. */
        @JavascriptInterface
        public void retomouOk() {
            PlayerService.retomouOk(MainActivity.this);
        }

        @JavascriptInterface
        public void parado() {
            PlayerService.setFonte(MainActivity.this, "", "", "", "", "", 0L, 0L, false);
        }

        @android.webkit.JavascriptInterface
        public boolean copiarTextoPix(String texto) {
            if (!"e0147a61-18b6-4cf0-972e-74d3cf38c90a".equals(texto)) return false;
            try {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard == null) return false;
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Chave Pix NEXUS VIDEO", texto));
                return true;
            } catch (Exception e) { return false; }
        }

        @android.webkit.JavascriptInterface
        public String versao() { return "2.6"; }

        @android.webkit.JavascriptInterface
        public void sair() { MainActivity.this.runOnUiThread(() -> finish()); }
    }

    // -------------------------------------------------------------- permissões

    private void pedirPermissoes() {
        java.util.List<String> faltando = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) faltando.add(Manifest.permission.READ_MEDIA_VIDEO);
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) faltando.add(Manifest.permission.POST_NOTIFICATIONS);
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            faltando.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            faltando.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!faltando.isEmpty()) requestPermissions(faltando.toArray(new String[0]), PEDIDO_PERMISSAO);
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permissoes, int[] resultados) {
        super.onRequestPermissionsResult(codigo, permissoes, resultados);
        if (codigo != PEDIDO_PERMISSAO) return;
        for (int i = 0; i < permissoes.length; i++)
            if (resultados[i] != PackageManager.PERMISSION_GRANTED) Log.w(TAG, "permissão negada: " + permissoes[i]);
        biblioteca.recarregar();
    }

    // -------------------------------------------------------------- ciclo de vida

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            webView.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null);
            return;
        }
        long agora = System.currentTimeMillis();
        if (agora - ultimoVoltar < 2000) {
            if (webView != null) webView.evaluateJavascript("window.nxPausarTudo&&window.nxPausarTudo()", null);
            PlayerService.encerrar(this);
            super.onBackPressed(); return;
        }
        ultimoVoltar = agora;
        if (webView != null) webView.evaluateJavascript("window.nxAvisoVoltar&&window.nxAvisoVoltar()", null);
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.evaluateJavascript("window.nxSnapshotLifecycle&&window.nxSnapshotLifecycle()", null);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        PlayerService.activityEmSegundoPlano();
        /* App saindo da tela. O JS manda fonte + fila + posição numa chamada atômica. */
        if (webView != null) {
            try { webView.evaluateJavascript("window.nxSalvarFila&&window.nxSalvarFila()", null); }
            catch (Exception ignored) {}
        }
        PlayerService.appEmSegundoPlano();
    }

    @Override
    protected void onStart() {
        super.onStart();
        PlayerService.appEmPrimeiroPlano();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            if (webView != null) webView.evaluateJavascript("window.nxPausarTudo&&window.nxPausarTudo()", null);
            PlayerService.encerrar(this);
        }
        super.onDestroy();
        if (servidor != null) servidor.parar();
        if (webView != null) { webView.loadUrl("about:blank"); webView.destroy(); webView = null; }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlayerService.appEmPrimeiroPlano();
        PlayerService.voltarATela(this);
        tentarLinkCompartilhado();
    }

    // -------------------------------------------------------------- foco áudio

    private android.media.AudioManager audioManager;
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
    }

    private void tentarLinkCompartilhado() {
        try {
            android.content.Intent intent = getIntent();
            if (intent == null || !android.content.Intent.ACTION_SEND.equals(intent.getAction())) return;
            String texto = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
            if (texto == null || texto.isEmpty()) return;
            intent.setAction(null);
            if (webView != null)
                webView.evaluateJavascript("window.nxLinkRecebido&&window.nxLinkRecebido(" + org.json.JSONObject.quote(texto) + ")", null);
        } catch (Exception e) { Log.w(TAG, "link: " + e); }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent); setIntent(intent); tentarLinkCompartilhado();
    }
}