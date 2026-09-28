package com.leo.nexusvideo;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor HTTP local que serve a interface e os vídeos para o WebView.
 *
 * <p>Rotas:
 * <ul>
 *   <li>{@code GET  /}                    → interface (asset index.html)</li>
 *   <li>{@code GET  /api/library}         → biblioteca de vídeos do aparelho</li>
 *   <li>{@code GET  /api/youtube/search}  → pesquisa no YouTube</li>
 *   <li>{@code POST /api/youtube/qualidades} → o que dá para baixar + opinião</li>
 *   <li>{@code POST /api/youtube}         → inicia o download</li>
 *   <li>{@code GET  /api/youtube/status}  → progresso</li>
 *   <li>{@code POST /api/youtube/cancel}  → cancela</li>
 *   <li>{@code GET  /video/&lt;id&gt;}         → o vídeo (com Range, para o player)</li>
 *   <li>{@code GET  /fonts/*, /img/*}     → assets da interface</li>
 * </ul>
 */
public class NexusServer {

    private static final String TAG = "NexusVideoSrv";

    /**
     * Portas fixas: o localStorage do WebView é separado por porta, então a
     * origem precisa ser estável — senão os favoritos parecem sumir.
     */
    private static final int[] PORTAS = {8577, 8578, 8579};

    private final Context ctx;
    private final VideoLibrary library;
    private final YtVideoDownload baixador;

    private ServerSocket socket;
    private int porta;
    private volatile boolean rodando;
    private Thread thread;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    public NexusServer(Context ctx, VideoLibrary library, YtVideoDownload baixador) {
        this.ctx = ctx;
        this.library = library;
        this.baixador = baixador;
    }

    public int getPorta() { return porta; }

    public int iniciar() throws IOException {
        IOException ultimo = null;
        for (int t : PORTAS) {
            try {
                socket = new ServerSocket(t, 16, InetAddress.getByName("127.0.0.1"));
                porta = t;
                break;
            } catch (IOException e) {
                ultimo = e;
                Log.w(TAG, "porta " + t + " indisponível: " + e.getMessage());
            }
        }
        if (socket == null) {
            throw ultimo != null ? ultimo : new IOException("sem porta livre");
        }
        rodando = true;
        thread = new Thread(this::aceitarLaco, "nexus-video-server");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "servidor no ar em http://127.0.0.1:" + porta);
        return porta;
    }

    public void parar() {
        rodando = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
    }

    private void aceitarLaco() {
        while (rodando) {
            try {
                final Socket cliente = socket.accept();
                pool.execute(() -> atender(cliente));
            } catch (IOException e) {
                if (rodando) Log.w(TAG, "aceitar: " + e.getMessage());
            }
        }
    }

    private void atender(Socket cliente) {
        try {
            cliente.setSoTimeout(30_000);
            PushbackInputStream in = new PushbackInputStream(
                    new BufferedInputStream(cliente.getInputStream(), 8192), 1);

            String linhaPedido = lerLinha(in);
            if (linhaPedido == null || linhaPedido.isEmpty()) return;

            String[] partes = linhaPedido.split(" ");
            String metodo = partes.length > 0 ? partes[0] : "GET";
            String alvo = partes.length > 1 ? partes[1] : "/";

            Map<String, String> cabecalhos = new HashMap<>();
            String linha;
            while ((linha = lerLinha(in)) != null && !linha.isEmpty()) {
                int p = linha.indexOf(':');
                if (p > 0) {
                    cabecalhos.put(linha.substring(0, p).trim().toLowerCase(Locale.ROOT),
                            linha.substring(p + 1).trim());
                }
            }

            byte[] corpo = null;
            if ("POST".equalsIgnoreCase(metodo)) {
                int tamanho = 0;
                try {
                    tamanho = Integer.parseInt(cabecalhos.getOrDefault("content-length", "0"));
                } catch (NumberFormatException ignored) { }
                if (tamanho > 0) {
                    corpo = new byte[tamanho];
                    int lidos = 0;
                    while (lidos < tamanho) {
                        int n = in.read(corpo, lidos, tamanho - lidos);
                        if (n < 0) break;
                        lidos += n;
                    }
                }
            }

            OutputStream saida = new BufferedOutputStream(cliente.getOutputStream(), 32 * 1024);
            tratar(metodo, alvo, cabecalhos, corpo, saida, cliente);
            saida.flush();
        } catch (Exception e) {
            Log.w(TAG, "atender: " + e);
        } finally {
            try { cliente.close(); } catch (IOException ignored) { }
        }
    }

    private void tratar(String metodo, String alvoBruto, Map<String, String> cabecalhos,
                        byte[] corpo, OutputStream saida, Socket cliente) throws Exception {
        String caminho = alvoBruto;
        String consulta = "";
        int i = caminho.indexOf('?');
        if (i >= 0) {
            consulta = caminho.substring(i + 1);
            caminho = caminho.substring(0, i);
        }
        caminho = java.net.URLDecoder.decode(caminho, "UTF-8");

        // ---------------------------------------------------------------- //
        if (caminho.equals("/") || caminho.equals("/index.html")) {
            responder(saida, 200, "text/html; charset=utf-8", lerAsset("index.html"));
            return;
        }

        if (caminho.equals("/api/library")) {
            responder(saida, 200, "application/json; charset=utf-8", library.comoJson());
            return;
        }

        if (caminho.equals("/api/youtube/status")) {
            responder(saida, 200, "application/json; charset=utf-8", baixador.statusJson());
            return;
        }

        if (caminho.equals("/api/youtube/cancel") && "POST".equalsIgnoreCase(metodo)) {
            responder(saida, 200, "application/json; charset=utf-8", baixador.cancelar());
            return;
        }

        if (caminho.equals("/api/youtube/search")) {
            String termo = parametro(consulta, "q");
            responder(saida, 200, "application/json; charset=utf-8", baixador.pesquisar(termo));
            return;
        }

        if (caminho.equals("/api/youtube/previa") && "POST".equalsIgnoreCase(metodo)) {
            String texto = corpo == null ? "" : new String(corpo, StandardCharsets.UTF_8);
            responder(saida, 200, "application/json; charset=utf-8",
                    baixador.previa(extrair("url", texto)));
            return;
        }

        if (caminho.equals("/api/youtube/qualidades") && "POST".equalsIgnoreCase(metodo)) {
            String texto = corpo == null ? "" : new String(corpo, StandardCharsets.UTF_8);
            responder(saida, 200, "application/json; charset=utf-8",
                    baixador.qualidades(extrair("url", texto)));
            return;
        }

        if (caminho.equals("/api/youtube") && "POST".equalsIgnoreCase(metodo)) {
            String texto = corpo == null ? "" : new String(corpo, StandardCharsets.UTF_8);
            responder(saida, 200, "application/json; charset=utf-8",
                    baixador.iniciar(extrair("url", texto), extrair("qualidade", texto)));
            return;
        }

        // ---------------------------------------------------------------- assets
        if (caminho.startsWith("/img/") || caminho.startsWith("/fonts/")) {
            String nome = caminho.substring(1);
            if (nome.contains("..")) {
                responder(saida, 404, "text/plain; charset=utf-8", "404");
                return;
            }
            try {
                byte[] dados = lerAsset(nome);
                String tipo = nome.endsWith(".png") ? "image/png"
                        : nome.endsWith(".jpg") || nome.endsWith(".jpeg") ? "image/jpeg"
                        : nome.endsWith(".woff2") ? "font/woff2"
                        : nome.endsWith(".ttf") ? "font/ttf"
                        : "application/octet-stream";
                responder(saida, 200, tipo, dados);
            } catch (IOException e) {
                responder(saida, 404, "text/plain; charset=utf-8", "404");
            }
            return;
        }

        // ---------------------------------------------------------------- vídeo
        if (caminho.startsWith("/video/")) {
            String id = caminho.substring("/video/".length());
            servirVideo(saida, id, cabecalhos);
            return;
        }

        if (caminho.startsWith("/thumb/")) {
            String id = caminho.substring("/thumb/".length());
            byte[] miniatura = library.miniatura(id);
            if (miniatura == null) {
                responder(saida, 404, "text/plain; charset=utf-8", "404");
            } else {
                responder(saida, 200, "image/jpeg", miniatura);
            }
            return;
        }

/* O proxy local aceita vídeo e áudio (stream progressivo) e preserva Range. */
        if (caminho.equals("/api/youtube/media") || caminho.equals("/api/youtube/audio")) {
            String alvo = parametro(consulta, "u");
            if (alvo == null || !alvo.startsWith("http")) {
                responder(saida, 400, "text/plain; charset=utf-8", "400");
                return;
            }
            redirecionarAudio(saida, alvo, cabecalhos);
            return;
        }

        responder(saida, 404, "text/plain; charset=utf-8", "404");
    }

    /**
     * Entrega o áudio da prévia com suporte a Range (o player precisa disso
     * para buscar/avançar). O destino é sempre uma URL do YouTube vinda do
     * extrator — nunca texto arbitrário do usuário.
     */
    private void redirecionarAudio(OutputStream saida, String alvo,
                                   Map<String, String> cabecalhos) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL(alvo).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/110.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");

        String faixa = cabecalhos.get("range");
        if (faixa != null) c.setRequestProperty("Range", faixa);

        int codigo = c.getResponseCode();
        long total = c.getContentLengthLong();
        String contentRange = c.getHeaderField("Content-Range");

        StringBuilder cab = new StringBuilder();
        cab.append("HTTP/1.1 ").append(codigo == 206 ? "206 Partial Content" : "200 OK")
                .append("\r\n");
        cab.append("Content-Type: ").append(
                c.getContentType() == null ? "audio/mpeg" : c.getContentType()).append("\r\n");
        cab.append("Accept-Ranges: bytes\r\n");
        cab.append("Content-Length: ").append(total).append("\r\n");
        if (contentRange != null) cab.append("Content-Range: ").append(contentRange).append("\r\n");
        cab.append("Cache-Control: no-store\r\n");
        cab.append("Connection: close\r\n\r\n");
        saida.write(cab.toString().getBytes(StandardCharsets.US_ASCII));

        try (InputStream in = new BufferedInputStream(c.getInputStream(), 128 * 1024)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) saida.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ //
    //  Vídeo (Range é obrigatório: sem ele o player não deixa avançar)
    // ------------------------------------------------------------------ //

    private void servirVideo(OutputStream saida, String id,
                             Map<String, String> cabecalhos) throws Exception {
        long total = library.tamanhoDe(id);
        if (total <= 0) {
            responder(saida, 404, "text/plain", "404");
            return;
        }

        long inicio = 0;
        long fim = total - 1;
        boolean parcial = false;

        String faixa = cabecalhos.get("range");
        if (faixa != null && faixa.startsWith("bytes=")) {
            String[] p = faixa.substring(6).split("-");
            try {
                if (!p[0].isEmpty()) inicio = Long.parseLong(p[0]);
                if (p.length > 1 && !p[1].isEmpty())
                    fim = Math.min(Long.parseLong(p[1]), total - 1);
                parcial = true;
            } catch (NumberFormatException ignored) { }
        }
        if (inicio > fim || inicio >= total) {
            inicio = 0;
            fim = total - 1;
            parcial = false;
        }

        long comprimento = fim - inicio + 1;

        StringBuilder cab = new StringBuilder();
        cab.append("HTTP/1.1 ").append(parcial ? "206 Partial Content" : "200 OK").append("\r\n");
        cab.append("Content-Type: ").append(library.mimeDe(id)).append("\r\n");
        cab.append("Accept-Ranges: bytes\r\n");
        cab.append("Content-Length: ").append(comprimento).append("\r\n");
        if (parcial) {
            cab.append("Content-Range: bytes ").append(inicio).append("-").append(fim)
                    .append("/").append(total).append("\r\n");
        }
        cab.append("Cache-Control: no-store\r\n");
        cab.append("Connection: close\r\n\r\n");
        saida.write(cab.toString().getBytes(StandardCharsets.US_ASCII));

        try (InputStream fluxo = library.abrirVideo(id)) {
            pular(fluxo, inicio);
            byte[] buffer = new byte[64 * 1024];
            long restante = comprimento;
            while (restante > 0) {
                int n = fluxo.read(buffer, 0, (int) Math.min(buffer.length, restante));
                if (n < 0) break;
                saida.write(buffer, 0, n);
                restante -= n;
            }
        }
    }

    private static void pular(InputStream in, long quantos) throws IOException {
        long resta = quantos;
        byte[] lixo = new byte[8192];
        while (resta > 0) {
            int n = in.read(lixo, 0, (int) Math.min(lixo.length, resta));
            if (n < 0) break;
            resta -= n;
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    private void responder(OutputStream saida, int codigo, String tipo, byte[] dados)
            throws IOException {
        StringBuilder cab = new StringBuilder();
        cab.append("HTTP/1.1 ").append(codigo)
                .append(codigo == 200 ? " OK" : codigo == 206 ? " Partial" : " X")
                .append("\r\n");
        cab.append("Content-Type: ").append(tipo).append("\r\n");
        cab.append("Content-Length: ").append(dados.length).append("\r\n");
        cab.append("Cache-Control: no-store\r\n");
        cab.append("Connection: close\r\n\r\n");
        saida.write(cab.toString().getBytes(StandardCharsets.US_ASCII));
        saida.write(dados);
    }

    private void responder(OutputStream saida, int codigo, String tipo, String texto)
            throws IOException {
        responder(saida, codigo, tipo, texto.getBytes(StandardCharsets.UTF_8));
    }

    private static String lerLinha(InputStream in) throws IOException {
        ByteArrayOutputStream acc = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') acc.write(c);
        }
        if (c < 0 && acc.size() == 0) return null;
        return new String(acc.toByteArray(), StandardCharsets.UTF_8);
    }

    private byte[] lerAsset(String nome) throws IOException {
        AssetManager am = ctx.getAssets();
        try (InputStream is = am.open(nome)) {
            ByteArrayOutputStream saida = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = is.read(buffer)) > 0) saida.write(buffer, 0, n);
            return saida.toByteArray();
        }
    }

    private static String parametro(String consulta, String chave) {
        if (consulta == null || consulta.isEmpty()) return "";
        for (String par : consulta.split("&")) {
            int eq = par.indexOf('=');
            if (eq > 0 && par.substring(0, eq).equals(chave)) {
                try {
                    return java.net.URLDecoder.decode(par.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return par.substring(eq + 1);
                }
            }
        }
        return "";
    }

    /**
     * Lê um campo de um JSON simples ({"url":"...","qualidade":"..."}).
     * Não é um parser: evita dependência só para dois campos.
     */
    static String extrair(String chave, String json) {
        if (json == null || json.isEmpty()) return "";
        String marca = "\"" + chave + "\"";
        int i = json.indexOf(marca);
        if (i < 0) return "";
        int d = json.indexOf(':', i + marca.length());
        if (d < 0) return "";
        int inicio = json.indexOf('"', d + 1);
        if (inicio < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int k = inicio + 1; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '\\' && k + 1 < json.length()) {
                char prox = json.charAt(k + 1);
                if (prox == '"' || prox == '\\') { sb.append(prox); k++; continue; }
                if (prox == 'n') { sb.append('\n'); k++; continue; }
                sb.append(prox);
                k++;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    /** Escapa texto para JSON (usado por toda a camada que monta JSON na mão). */
    public static String escapar(String texto) {
        if (texto == null) return "";
        StringBuilder sb = new StringBuilder(texto.length() + 16);
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
