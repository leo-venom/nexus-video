package com.leo.nexusvideo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Botões do fone Bluetooth / com fio.
 *
 * <p>Sem um {@code BroadcastReceiver} registrado para {@code ACTION_MEDIA_BUTTON},
 * o Android entrega o toque do fone para outra sessão de mídia — o NEXUS VIDEO
 * não recebia play/pause. Aqui o KeyEvent é traduzido para as ações do
 * {@link PlayerService}.</p>
 */
public class BotaoFoneReceiver extends BroadcastReceiver {

    private static final String TAG = "NexusVideoFone";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ctx == null || intent == null) return;
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;

        KeyEvent evento = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (evento == null) return;

        /* Só a descida: o fone manda ACTION_DOWN + ACTION_UP e tratando as duas
           o play/pause seria aplicado duas vezes (tocava e pausava). */
        if (evento.getAction() != KeyEvent.ACTION_DOWN) return;

        String acao = acaoDe(evento.getKeyCode());
        if (acao == null) return;

        Log.i(TAG, "botao do fone " + evento.getKeyCode() + " -> " + acao);
        PlayerService.peloFone(ctx, acao);
    }

    private static String acaoDe(int codigo) {
        switch (codigo) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return "play";
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return "pause";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return "toggle";
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return "next";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return "prev";
            default:
                return null;
        }
    }
}