package com.leo.nexusvideo;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decide QUAIS qualidades realmente podem ser baixadas e o que dizer sobre cada
 * uma. Toda a lógica aqui é pura (sem Android e sem rede), para poder ser testada.
 *
 * <p><b>O problema real do YouTube:</b> só existe UM stream progressivo (vídeo +
 * áudio no mesmo arquivo) e ele é sempre 360p. Qualquer resolução acima disso vem
 * em dois pedaços: um arquivo só de vídeo e outro só de áudio, que precisam ser
 * JUNTADOS depois de baixados.</p>
 *
 * <p>Cada qualidade recebe uma nota (a "opinião") que diz ao usuário exatamente o
 * que esperar antes de gastar tempo e dados baixando.</p>
 */
public final class QualidadeVideo {

    private QualidadeVideo() {}

    /** Como a qualidade é obtida. */
    public enum Tipo {
        /** Vídeo + áudio num arquivo só: baixa e toca, sem juntar nada. */
        DIRETA,
        /** Vídeo e áudio separados: o app baixa os dois e junta no fim. */
        JUNTAR,
        /** Só vídeo sem áudio — o usuário tem de saber disso. */
        MUDO
    }

    /** Uma opção oferecida ao usuário. */
    public static final class Opcao {
        public final String rotulo;
        public final int altura;
        public final Tipo tipo;
        public final String nota;
        public final VideoStream video;
        public final boolean mp4;

        Opcao(String rotulo, int altura, Tipo tipo, String nota, VideoStream video, boolean mp4) {
            this.rotulo = rotulo;
            this.altura = altura;
            this.tipo = tipo;
            this.nota = nota;
            this.video = video;
            this.mp4 = mp4;
        }

        boolean pronta() {
            return tipo == Tipo.DIRETA;
        }

        String tipoJson() {
            switch (tipo) {
                case DIRETA: return "direta";
                case JUNTAR: return "juntar";
                default: return "mudo";
            }
        }

        /** Extensão/container que o app deve produzir para esta opção. */
        public String container() {
            return mp4 ? "mp4" : "webm";
        }
    }

    /** Altura em pixels a partir de uma resolução tipo "1080p60" / "720p". */
    static int alturaDe(String resolucao) {
        if (resolucao == null) return 0;
        String digitos = resolucao.replaceAll("[^0-9]", "");
        // "1080p60" -> "108060"; a altura são os 3-4 primeiros dígitos
        if (digitos.length() > 4) digitos = digitos.substring(0, digitos.length() - 2);
        try {
            return Integer.parseInt(digitos);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Prefere MP4 (H.264/AAC) porque é o que toca em qualquer aparelho e permite juntar. */
    static boolean ehMp4(VideoStream v) {
        return v.getFormat() == MediaFormat.MPEG_4 || "mp4".equalsIgnoreCase(safeExt(v));
    }

    private static String safeExt(VideoStream v) {
        try {
            return v.getFormat().getSuffix();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Monta a lista de opções de download, da melhor para a pior.
     *
     * @param progressivos streams com áudio embutido (normalmente só 360p)
     * @param adaptativos  streams só de vídeo (720p, 1080p, ...)
     */
    public static List<Opcao> montar(List<VideoStream> progressivos,
                                     List<VideoStream> adaptativos) {
        if (progressivos == null) progressivos = new ArrayList<>();
        if (adaptativos == null) adaptativos = new ArrayList<>();

        // uma opção por ALTURA. Quando a mesma resolução existe das duas formas,
        // fica a de MP4 (H.264), que é mais compatível; senão a que houver.
        Map<Integer, Opcao> porAltura = new LinkedHashMap<>();

        // 1) adaptativos (exigem juntar), do maior para o menor
        List<VideoStream> ordenados = new ArrayList<>(adaptativos);
        ordenados.sort((a, b) -> Integer.compare(alturaDe(b.getResolution()),
                alturaDe(a.getResolution())));

        for (VideoStream v : ordenados) {
            int alt = alturaDe(v.getResolution());
            if (alt <= 0) continue;
            Opcao existente = porAltura.get(alt);
            // já tem uma opção desta altura? só troca se a nova for MP4 e a atual não
            if (existente != null && !(!existente.mp4 && ehMp4(v))) continue;

            String nota;
            if (!ehMp4(v)) {
                nota = "Formato WebM (VP9/Opus). Salvo em .webm — recomendado apenas "
                        + "se você quiser a resolução máxima disponível.";
            } else if (alt >= 1440) {
                nota = "Alta definição. Baixa vídeo e áudio separados e junta no fim — "
                        + "demora mais e usa mais dados.";
            } else if (alt >= 1080) {
                nota = "Full HD. Baixa vídeo e áudio separados e junta no fim.";
            } else {
                nota = "Vídeo e áudio separados, juntados no fim do download.";
            }
            porAltura.put(alt, new Opcao(alt + "p", alt, Tipo.JUNTAR, nota, v, ehMp4(v)));
        }

        // 2) progressivos (arquivo único com áudio): substituem o adaptativo da
        //    MESMA altura, porque entregam igual sem precisar juntar
        for (VideoStream v : progressivos) {
            int alt = alturaDe(v.getResolution());
            String rotulo = alt > 0 ? alt + "p" : "Padrão";
            Opcao direta = new Opcao(rotulo, alt, Tipo.DIRETA,
                    "Arquivo único com áudio e vídeo. Baixa mais rápido e toca em "
                            + "qualquer aparelho.", v, ehMp4(v));
            if (alt > 0) {
                porAltura.put(alt, direta);   // sobrescreve a versão que exigia juntar
            } else {
                porAltura.put(-1 - porAltura.size(), direta);
            }
        }

        List<Opcao> opcoes = new ArrayList<>(porAltura.values());
        opcoes.sort((a, b) -> Integer.compare(b.altura, a.altura));
        return opcoes;
    }

    /**
     * Escolhe a melhor opção que o app consegue ENTREGAR de fato.
     * Prefere a maior resolução; em empate, a que já vem com áudio.
     */
    public static Opcao melhor(List<Opcao> opcoes) {
        if (opcoes == null || opcoes.isEmpty()) return null;
        Opcao melhor = null;
        for (Opcao o : opcoes) {
            if (o.tipo == Tipo.MUDO) continue;   // nunca escolher sem áudio sozinho
            if (melhor == null) {
                melhor = o;
                continue;
            }
            if (o.altura > melhor.altura) melhor = o;
            else if (o.altura == melhor.altura && o.pronta() && !melhor.pronta()) melhor = o;
        }
        return melhor != null ? melhor : opcoes.get(0);
    }
}
