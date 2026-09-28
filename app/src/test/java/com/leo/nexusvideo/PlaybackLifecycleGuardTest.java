package com.leo.nexusvideo;

public final class PlaybackLifecycleGuardTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        PlaybackLifecycleGuard guard = new PlaybackLifecycleGuard();
        PlaybackLifecycleGuard.Snapshot playing = new PlaybackLifecycleGuard.Snapshot(
                "biblioteca", "42", "", "title", "NEXUS", 4321, 60000, true);
        guard.record(playing);
        guard.activityPausing(null);
        check(guard.pauseUpdateToIgnore(false) == playing, "automatic pause during onPause is ignored");
        guard.activityStopped();
        check(guard.backgroundSnapshot() == playing, "background retains active source and position");
        check(guard.pauseUpdateToIgnore(false) == playing, "delayed pause event after onStop ignored");
        check(guard.pauseUpdateToIgnore(true) == null, "playing update passes through");
        guard.explicitPause();
        check(guard.pauseUpdateToIgnore(false) == null, "explicit user pause honored");
        guard.activityPausing(null);
        guard.activityResumed();
        check(guard.pauseUpdateToIgnore(false) == null, "resume clears stale lifecycle state");
        PlaybackLifecycleGuard paused = new PlaybackLifecycleGuard();
        paused.record(new PlaybackLifecycleGuard.Snapshot("", "", "", "", "", 0, 0, false));
        paused.activityPausing(null);
        paused.activityStopped();
        check(paused.backgroundSnapshot() == null, "previously paused remains paused");
        System.out.println("PlaybackLifecycleGuard: " + checks + " checks passed");
    }
}
