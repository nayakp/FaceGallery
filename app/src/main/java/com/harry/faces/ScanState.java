package com.harry.faces;

import java.util.concurrent.CopyOnWriteArraySet;

/** In-process scan state shared by the foreground service and visible activities. */
final class ScanState {
    interface Listener { void onScanStateChanged(Snapshot snapshot); }
    enum Job { NONE, PHOTO_SCAN, REEVALUATE_UNNAMED }

    record Snapshot(
            boolean running,
            boolean pauseRequested,
            int processed,
            int total,
            int failed,
            String message,
            Job job) {
        int percent() {
            return total <= 0 ? 0 : Math.min(100, Math.round(processed * 100f / total));
        }
    }

    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile Snapshot current =
            new Snapshot(false, false, 0, 0, 0, "Ready • photos stay on your phone", Job.NONE);

    private ScanState() {}

    static Snapshot current() { return current; }

    static void publish(Snapshot next) {
        current = next;
        for (Listener listener : LISTENERS) listener.onScanStateChanged(next);
    }

    static void addListener(Listener listener) {
        LISTENERS.add(listener);
        listener.onScanStateChanged(current);
    }

    static void removeListener(Listener listener) { LISTENERS.remove(listener); }
}
