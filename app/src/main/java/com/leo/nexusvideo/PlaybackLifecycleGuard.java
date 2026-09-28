package com.leo.nexusvideo;

/** Separates WebView lifecycle pauses from explicit user pauses. */
final class PlaybackLifecycleGuard {
    static final class Snapshot {
        final String type, id, url, title, subtitle;
        final long positionMs, durationMs;
        final boolean playing;

        Snapshot(String type, String id, String url, String title, String subtitle,
                 long positionMs, long durationMs, boolean playing) {
            this.type = type == null ? "" : type;
            this.id = id == null ? "" : id;
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.positionMs = Math.max(0, positionMs);
            this.durationMs = Math.max(0, durationMs);
            this.playing = playing;
        }
    }

    private Snapshot lastKnown;
    private Snapshot beforePause;
    private boolean transitionPending;
    private boolean backgroundHandoff;

    synchronized void record(Snapshot snapshot) {
        if (snapshot == null) return;
        if (!snapshot.playing && (transitionPending || backgroundHandoff)) return;
        lastKnown = snapshot;
        if (snapshot.playing && transitionPending) beforePause = snapshot;
        if (snapshot.playing && backgroundHandoff) beforePause = snapshot;
    }

    synchronized void activityPausing(Snapshot snapshot) {
        if (snapshot != null && snapshot.playing) lastKnown = snapshot;
        beforePause = snapshot != null && snapshot.playing ? snapshot
                : (lastKnown != null && lastKnown.playing ? lastKnown : null);
        transitionPending = beforePause != null;
        backgroundHandoff = false;
    }

    synchronized void activityStopped() {
        backgroundHandoff = transitionPending && beforePause != null;
        transitionPending = false;
    }

    synchronized void beginBackgroundHandoff(Snapshot snapshot) {
        if (snapshot != null && snapshot.playing) {
            lastKnown = snapshot;
            beforePause = snapshot;
        }
        backgroundHandoff = beforePause != null;
        transitionPending = false;
    }

    synchronized Snapshot pauseUpdateToIgnore(boolean requestedPlaying) {
        if (requestedPlaying || beforePause == null) return null;
        return transitionPending || backgroundHandoff ? beforePause : null;
    }

    synchronized Snapshot backgroundSnapshot() {
        return backgroundHandoff ? beforePause : null;
    }

    synchronized void explicitPause() {
        if (lastKnown != null) {
            lastKnown = new Snapshot(lastKnown.type, lastKnown.id, lastKnown.url,
                    lastKnown.title, lastKnown.subtitle, lastKnown.positionMs,
                    lastKnown.durationMs, false);
        }
        beforePause = null;
        transitionPending = false;
        backgroundHandoff = false;
    }

    synchronized void activityResumed() {
        beforePause = null;
        transitionPending = false;
        backgroundHandoff = false;
    }
}
