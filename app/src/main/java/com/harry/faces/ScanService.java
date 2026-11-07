package com.harry.faces;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Performs resumable face recognition while the UI is backgrounded or the screen is locked. */
public final class ScanService extends Service {
    static final String ACTION_RESUME = "com.harry.faces.action.RESUME_SCAN";
    static final String ACTION_PAUSE = "com.harry.faces.action.PAUSE_SCAN";
    static final String ACTION_REEVALUATE_UNNAMED = "com.harry.faces.action.REEVALUATE_UNNAMED";

    private static final String CHANNEL_ID = "face_scan";
    private static final int NOTIFICATION_ID = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private Store store;
    private Engine engine;
    private PowerManager.WakeLock wakeLock;
    private long lastProgressUpdate;
    private long lastNotificationUpdate;
    private volatile boolean timeoutStopping;
    private volatile ScanState.Job currentJob=ScanState.Job.NONE;

    @Override public void onCreate() {
        super.onCreate();
        store = Store.get(getApplicationContext());
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESUME : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            requestPause();
            return START_NOT_STICKY;
        }

        boolean reevaluating=ACTION_REEVALUATE_UNNAMED.equals(action);
        currentJob=reevaluating?ScanState.Job.REEVALUATE_UNNAMED:ScanState.Job.PHOTO_SCAN;
        startInForeground(reevaluating?"Preparing unnamed-face re-evaluation…":"Preparing face scan…");
        if (!reevaluating&&!anyAccess()) {
            finishWithoutWorker("Photo access is needed to scan your gallery");
            return START_NOT_STICKY;
        }
        if (workerRunning.compareAndSet(false, true)) {
            pauseRequested.set(false);
            lastProgressUpdate=0;
            lastNotificationUpdate=0;
            acquireWakeLock();
            ScanState.publish(new ScanState.Snapshot(true, false, 0, 0, 0,
                    reevaluating?"Preparing unnamed faces…":"Preparing scan…",currentJob));
            executor.execute(reevaluating?this::reevaluateUnnamed:this::scan);
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        pauseRequested.set(true);
        executor.shutdownNow();
        releaseWakeLock();
        if (workerRunning.get() && !timeoutStopping) {
            ScanState.Snapshot old = ScanState.current();
            ScanState.publish(new ScanState.Snapshot(false, false, old.processed(), old.total(),
                    old.failed(), "Paused • progress saved",old.job()));
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onTimeout(int startId, int fgsType) {
        timeoutStopping = true;
        pauseRequested.set(true);
        ScanState.Snapshot old = ScanState.current();
        ScanState.publish(new ScanState.Snapshot(true, true, old.processed(), old.total(),
                old.failed(), "Pausing at Android's background-processing time limit…",old.job()));
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    static void resume(Context context) {
        Intent intent = new Intent(context, ScanService.class).setAction(ACTION_RESUME);
        context.startForegroundService(intent);
    }

    static void pause(Context context) {
        context.startService(new Intent(context, ScanService.class).setAction(ACTION_PAUSE));
    }

    static void reevaluateUnnamed(Context context) {
        Intent intent=new Intent(context,ScanService.class).setAction(ACTION_REEVALUATE_UNNAMED);
        context.startForegroundService(intent);
    }

    private void requestPause() {
        ScanState.Snapshot old = ScanState.current();
        if (!workerRunning.get()) {
            ScanState.publish(new ScanState.Snapshot(false, false, old.processed(), old.total(),
                    old.failed(), "Paused • progress saved",old.job()));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        pauseRequested.set(true);
        boolean reevaluating=old.job()==ScanState.Job.REEVALUATE_UNNAMED;
        String message=reevaluating?"Pausing after the current face group…":"Pausing after the current photo…";
        ScanState.publish(new ScanState.Snapshot(true, true, old.processed(), old.total(),
                old.failed(), message,old.job()));
        updateNotification(message);
    }

    private void scan() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        int complete = 0, restored = 0, skipped = 0, failed = 0, processed = 0, total = 0;
        try {
            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.SIZE};
            try (Cursor cursor = getContentResolver().query(collection, projection, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " ASC")) {
                if (cursor == null) throw new IllegalStateException("Cannot query photos");
                total = cursor.getCount();

                Set<String> present = new HashSet<>(Math.max(16, total * 2));
                while (cursor.moveToNext()) {
                    present.add(ContentUris.withAppendedId(collection, cursor.getLong(0)).toString());
                }
                cursor.moveToPosition(-1);

                Store.ScanSession session = store.beginScan();
                publishProgress(0, total, 0, progressText(0, total, 0));
                while (!pauseRequested.get() && cursor.moveToNext()) {
                    String uri = ContentUris.withAppendedId(collection, cursor.getLong(0)).toString();
                    Store.Photo photo = new Store.Photo(uri, cursor.getLong(1), cursor.getLong(2));
                    if (store.processed(photo)) {
                        if (!store.hasFingerprint(uri)) {
                            try { store.setFingerprint(uri, Engine.fingerprint(getApplicationContext(), uri)); }
                            catch (java.io.IOException error) { failed++; }
                        }
                        skipped++;
                    } else {
                        try {
                            String fingerprint = Engine.fingerprint(getApplicationContext(), uri);
                            if (store.relinkByFingerprint(photo, fingerprint, present)) {
                                restored++;
                            } else {
                                if (engine == null) engine = new Engine(getApplicationContext());
                                List<Store.Found> found = engine.recognize(getApplicationContext(), photo.uri());
                                store.commit(photo, fingerprint, found, session);
                                complete++;
                            }
                        } catch (java.io.IOException | RuntimeException error) {
                            failed++;
                        }
                    }
                    processed++;
                    publishProgress(processed, total, failed, progressText(processed, total, failed));
                }
                // Reconcile only after imported rows had a chance to relink to new
                // MediaStore URIs. A pre-scan cleanup would discard portable data.
                if (fullAccess() && !pauseRequested.get()) store.reconcile(present);
            }

            String finalMessage = (pauseRequested.get() ? "Paused" : "Scan complete") + " • "
                    + complete + " new, " + skipped + " already scanned"
                    + (restored > 0 ? ", " + restored + " restored from backup" : "")
                    + (failed > 0 ? ", " + failed + " unreadable (will retry)" : "")
                    + (fullAccess() ? "" : " • selected photos only");
            store.invalidatePeopleCache();
            ScanState.publish(new ScanState.Snapshot(false, false, processed, total, failed,
                    finalMessage,ScanState.Job.PHOTO_SCAN));
        } catch (Exception error) {
            String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            store.invalidatePeopleCache();
            ScanState.publish(new ScanState.Snapshot(false, false, processed, total, failed,
                    "Scan stopped: " + detail,ScanState.Job.PHOTO_SCAN));
        } finally {
            workerRunning.set(false);
            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private String progressText(int processed, int total, int failed) {
        return "Scanned " + processed + " / " + total + " • " + failed + " unreadable";
    }

    private void reevaluateUnnamed(){
        // This is a long, CPU-bound maintenance job. Keep UI and image loading ahead
        // of it even when the user continues browsing while it runs.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST);
        int processed=0,total=0,merged=0;
        try{
            Store.ReevaluationSession session=store.beginUnnamedReevaluation();
            List<Long> unnamed=store.unnamedPersonIds();total=unnamed.size();
            if(session.targetCount()==0){
                ScanState.publish(new ScanState.Snapshot(false,false,0,total,0,
                        "No face groups are available for re-evaluation",
                        ScanState.Job.REEVALUATE_UNNAMED));return;
            }
            publishProgress(0,total,0,reevaluationText(0,total,0));
            for(long person:unnamed){
                if(pauseRequested.get())break;
                if(store.reevaluateUnnamedPerson(person,session)>=0)merged++;
                processed++;publishProgress(processed,total,0,reevaluationText(processed,total,merged));
            }
            String message=(pauseRequested.get()?"Re-evaluation paused":"Re-evaluation complete")
                    +" • "+merged+" unnamed "+(merged==1?"group":"groups")+" matched";
            store.invalidatePeopleCache();
            ScanState.publish(new ScanState.Snapshot(false,false,processed,total,0,message,
                    ScanState.Job.REEVALUATE_UNNAMED));
        }catch(Exception error){
            String detail=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
            store.invalidatePeopleCache();
            ScanState.publish(new ScanState.Snapshot(false,false,processed,total,0,
                    "Re-evaluation stopped: "+detail,ScanState.Job.REEVALUATE_UNNAMED));
        }finally{
            workerRunning.set(false);releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
    }

    private String reevaluationText(int processed,int total,int merged){
        return "Checked "+processed+" / "+total+" unnamed groups • "+merged+" matched";
    }

    private void publishProgress(int processed, int total, int failed, String message) {
        long now = SystemClock.elapsedRealtime();
        if(processed==0||processed==total||now-lastProgressUpdate>=200){
            lastProgressUpdate=now;
            ScanState.publish(new ScanState.Snapshot(true, pauseRequested.get(), processed, total,
                    failed, message,currentJob));
        }
        if (!timeoutStopping && (processed == total || now - lastNotificationUpdate >= 1000)) {
            lastNotificationUpdate = now;
            updateNotification(message);
        }
    }

    private void finishWithoutWorker(String message) {
        ScanState.publish(new ScanState.Snapshot(false, false, 0, 0, 0, message,currentJob));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void startInForeground(String message) {
        Notification notification = notification(message);
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification notification(String message) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent pause = new Intent(this, ScanService.class).setAction(ACTION_PAUSE);
        PendingIntent pauseAction = PendingIntent.getService(this, 2, pause,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean reevaluating=currentJob==ScanState.Job.REEVALUATE_UNNAMED;
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.harry.faces.R.drawable.ic_faces_scan)
                .setContentTitle(reevaluating?"Faces is re-evaluating":"Faces is scanning")
                .setContentText(message)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                        "Pause", pauseAction).build())
                .build();
    }

    private void updateNotification(String message) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,
                notification(message));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Face recognition",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows face-processing progress and pause control.");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FacesGallery:scan");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(8L * 60L * 60L * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private boolean fullAccess() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean anyAccess() {
        return fullAccess() || (Build.VERSION.SDK_INT >= 34
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                == PackageManager.PERMISSION_GRANTED);
    }
}
