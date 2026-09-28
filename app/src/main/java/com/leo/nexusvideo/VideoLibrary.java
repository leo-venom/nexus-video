package com.leo.nexusvideo;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Biblioteca de VÍDEOS do aparelho.
 *
 * <p>A página principal mostra <b>somente o que este app baixou</b>, ou seja, os
 * vídeos da pasta {@code Movies/NexusVideo}. Os demais vídeos do celular não
 * entram na listagem.</p>
 */
public class VideoLibrary {

    private static final String TAG = "NexusVideoLib";

    /** Pasta onde o app grava — única origem da listagem. */
    static final String SUBPASTA = Environment.DIRECTORY_MOVIES + "/NexusVideo/";

    private static final String PASTA_AVULSO = "OUTROS";

    private final Context ctx;
    private final ContentResolver resolver;
    private final List<Video> videos = new ArrayList<>();
    private long ultimaVarredura = 0;

    public static class Video {
        public String id;
        public String nome;
        public String pasta;
        public long duracaoMs;
        public long tamanho;
        public int largura;
        public int altura;
        public long adicionado;
        public String caminho;
    }

    public VideoLibrary(Context ctx) {
        this.ctx = ctx;
        this.resolver = ctx.getContentResolver();
    }

    // ------------------------------------------------------------------ //
    //  Varredura
    // ------------------------------------------------------------------ //

    public synchronized void recarregar() {
        List<Video> nova = new ArrayList<>();

        Uri colecao = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] colunas = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DATE_ADDED,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? MediaStore.Video.Media.RELATIVE_PATH
                        : MediaStore.Video.Media.DATA,
        };

        /* Somente a pasta do app. No Android 10+ filtra pelo RELATIVE_PATH;
           antes disso a coluna é DATA, e o LIKE abaixo cobre os dois casos. */
        String selecao;
        String[] argumentos;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selecao = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
            argumentos = new String[]{SUBPASTA + "%"};
        } else {
            selecao = MediaStore.Video.Media.DATA + " LIKE ?";
            argumentos = new String[]{"%" + SUBPASTA + "%"};
        }

        try (Cursor c = resolver.query(colecao, colunas, selecao, argumentos,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {

            if (c == null) {
                Log.w(TAG, "MediaStore devolveu null (permissão?)");
                return;
            }

            int iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int iNome = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int iTam = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int iLar = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH);
            int iAlt = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT);
            int iAdd = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int iCam = c.getColumnIndex(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Video.Media.RELATIVE_PATH
                    : MediaStore.Video.Media.DATA);

            while (c.moveToNext()) {
                Video v = new Video();
                v.id = String.valueOf(c.getLong(iId));
                v.nome = c.getString(iNome);
                v.duracaoMs = c.getLong(iDur);
                v.tamanho = c.getLong(iTam);
                v.largura = c.getInt(iLar);
                v.altura = c.getInt(iAlt);
                v.adicionado = c.getLong(iAdd);
                v.caminho = iCam >= 0 ? c.getString(iCam) : null;
                v.pasta = pastaDoCaminho(v.caminho);

                if (v.nome == null || v.nome.isEmpty()) v.nome = "Vídeo " + v.id;
                if (v.largura <= 0 || v.altura <= 0) {
                    v.largura = 0;
                    v.altura = 0;
                }
                nova.add(v);
            }
        } catch (Exception e) {
            Log.w(TAG, "erro varrendo: " + e);
        }

        synchronized (this) {
            videos.clear();
            videos.addAll(nova);
            ultimaVarredura = System.currentTimeMillis();
        }

        Log.i(TAG, "biblioteca: " + videos.size() + " vídeos");
    }

    /**
     * A pasta sai do caminho:
     *   Movies/NexusVideo/xxx.mp4   -> "NEXUS VIDEO"
     *   Movies/Download/xxx.mp4     -> "DOWNLOAD"
     *   Movies/xxx.mp4              -> "OUTROS"
     */
    private static String pastaDoCaminho(String caminho) {
        if (caminho == null || caminho.isEmpty()) return PASTA_AVULSO;

        String limpo = caminho.replace('\\', '/');
        if (limpo.contains("Movies/")) {
            limpo = limpo.substring(limpo.indexOf("Movies/") + "Movies/".length());
        }
        int ultima = limpo.lastIndexOf('/');
        if (ultima < 0) return PASTA_AVULSO;
        String pasta = limpo.substring(0, ultima);
        if (pasta.isEmpty()) return PASTA_AVULSO;

        String primeira = pasta.split("/")[0];
        if (primeira.isEmpty()) return PASTA_AVULSO;
        return primeira.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ //
    //  JSON
    // ------------------------------------------------------------------ //

    public synchronized String comoJson() {
        Map<String, Integer> contagem = new LinkedHashMap<>();
        for (Video v : videos) contagem.merge(v.pasta, 1, Integer::sum);

        StringBuilder json = new StringBuilder(32 * 1024);
        json.append("{\"total\":").append(videos.size()).append(",\"videos\":[");

        for (int i = 0; i < videos.size(); i++) {
            Video v = videos.get(i);
            if (i > 0) json.append(',');
            json.append('{')
                    .append("\"id\":\"").append(v.id).append("\",")
                    .append("\"nome\":\"").append(NexusServer.escapar(v.nome)).append("\",")
                    .append("\"pasta\":\"").append(NexusServer.escapar(v.pasta)).append("\",")
                    .append("\"url\":\"/video/").append(v.id).append("\",")
                    .append("\"duracao\":").append(v.duracaoMs / 1000.0).append(',')
                    .append("\"tamanho\":").append(v.tamanho).append(',')
                    .append("\"largura\":").append(v.largura).append(',')
                    .append("\"altura\":").append(v.altura).append(',')
                    .append("\"adicionado\":").append(v.adicionado)
                    .append('}');
        }

        json.append("],\"pastas\":[");
        boolean primeiro = true;
        for (Map.Entry<String, Integer> e : contagem.entrySet()) {
            if (!primeiro) json.append(',');
            primeiro = false;
            json.append("{\"nome\":\"").append(NexusServer.escapar(e.getKey()))
                    .append("\",\"total\":").append(e.getValue()).append('}');
        }
        json.append("]}");
        return json.toString();
    }

    public synchronized int total() {
        return videos.size();
    }

    // ------------------------------------------------------------------ //
    //  Acesso aos arquivos
    // ------------------------------------------------------------------ //

    public Uri uriPublicaVideo(String id) {
        return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                Long.parseLong(id));
    }

    private Uri uriDe(String id) { return uriPublicaVideo(id); }

    public String mimeDe(String id) {
        try (Cursor c = resolver.query(uriDe(id),
                new String[]{MediaStore.Video.Media.MIME_TYPE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String m = c.getString(0);
                if (m != null && !m.isEmpty()) return m;
            }
        } catch (Exception ignored) {
        }
        return "video/mp4";
    }

    public long tamanhoDe(String id) {
        try (InputStream is = resolver.openInputStream(uriDe(id))) {
            if (is == null) return -1;
            try (android.content.res.AssetFileDescriptor afd =
                         resolver.openAssetFileDescriptor(uriDe(id), "r")) {
                if (afd != null && afd.getLength() > 0) return afd.getLength();
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
        try (Cursor c = resolver.query(uriDe(id),
                new String[]{MediaStore.Video.Media.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    public InputStream abrirVideo(String id) throws Exception {
        return resolver.openInputStream(uriDe(id));
    }

    /**
     * Miniatura do vídeo (JPEG), usada nos cards da biblioteca.
     *
     * <p>Usa {@code ContentResolver.loadThumbnail}, disponível do Android 10 em
     * diante. Em versões anteriores devolve {@code null} e o front mostra o card
     * sem imagem — não vale carregar uma biblioteca só para isso.</p>
     */
    public byte[] miniatura(String id) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        try {
            android.graphics.Bitmap bmp = resolver.loadThumbnail(
                    uriDe(id), new android.util.Size(360, 360), null);
            if (bmp == null) return null;
            java.io.ByteArrayOutputStream saida = new java.io.ByteArrayOutputStream(32 * 1024);
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, saida);
            bmp.recycle();
            return saida.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "miniatura " + id + ": " + e.getMessage());
            return null;
        }
    }

    /** Ordena por data de adição (mais recentes primeiro). */
    public void ordenarPorRecentes() {
        synchronized (this) {
            videos.sort(Comparator.comparingLong((Video v) -> v.adicionado).reversed());
        }
    }

    public long ultimaVarredura() {
        return ultimaVarredura;
    }

    /** Caminho absoluto da pasta pública do app (usado só em Android 9 ou anterior). */
    public static File pastaPublica() {
        File pasta = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "NexusVideo");
        if (!pasta.exists() && !pasta.mkdirs()) {
            Log.w(TAG, "não consegui criar " + pasta);
        }
        return pasta;
    }
}
