package com.bridge.mx;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;

/**
 * Watches MX Player's {@code MediaSession} to track real watch progress — the
 * standard way media apps expose "what is playing, and how far in".
 *
 * <p>A normal app may read the sessions of other apps by holding an enabled
 * {@link NotificationListenerService}; the user grants that once under
 * Settings &gt; Notification access (the bridge opens that screen for you).
 *
 * <p>While an MX session exists we sample it every few seconds: the interpolated
 * position and the duration become a watched percent, which feeds the same
 * {@link Watched} ledger (and {@link Progress} reflection) the accessibility
 * reader uses. Completion uses the shared threshold. This gives exact position,
 * works with MX's controls hidden, and never needs a screen capture.
 */
public class MediaWatchService extends NotificationListenerService {

    private static final String TAG = "MediaWatch";
    /** MX Player (free and pro) package prefix. */
    private static final String MX = "com.mxtech.videoplayer.";
    private static final long POLL_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling;

    private final Runnable pollR = new Runnable() {
        @Override
        public void run() {
            boolean active = poll();
            if (active) handler.postDelayed(this, POLL_MS);
            else polling = false;
        }
    };

    @Override
    public void onListenerConnected() {
        ensureInit();
        Report.info("mediawatch", "notification listener connected");
        kick();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName() == null) return;
        if (sbn.getPackageName().startsWith(MX)) kick();
    }

    /** Only ever one poll chain running at a time. */
    private void kick() {
        if (polling) return;
        polling = true;
        handler.post(pollR);
    }

    private void ensureInit() {
        try {
            Store.init(this);
            Watched.init(this);
        } catch (Throwable t) {
            Report.err("mediawatch", "init failed", t);
        }
    }

    /**
     * Sample every active MX session once.
     *
     * @return true when at least one MX session was found (continue polling)
     */
    private boolean poll() {
        try {
            MediaSessionManager mgr =
                    (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (mgr == null) return false;
            List<MediaController> cs = mgr.getActiveSessions(
                    new ComponentName(this, MediaWatchService.class));
            if (cs == null) return false;
            boolean found = false;
            for (MediaController c : cs) {
                String pkg = c.getPackageName();
                if (pkg == null || !pkg.startsWith(MX)) continue;
                found = true;
                sample(c);
            }
            return found;
        } catch (SecurityException se) {
            // Notification access not granted yet — nothing else to do.
            Report.info("mediawatch", "no notification access; MediaSession unreadable");
            return false;
        } catch (Throwable t) {
            Report.err("mediawatch", "poll failed", t);
            return false;
        }
    }

    private void sample(MediaController c) {
        try {
            PlaybackState st = c.getPlaybackState();
            if (st == null) return;
            MediaMetadata md = c.getMetadata();
            long dur = md == null ? 0L : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            String title = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null && md != null) {
                title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            }
            if (title == null || title.isEmpty() || dur <= 0) return;

            boolean playing = st.getState() == PlaybackState.STATE_PLAYING;
            long live = MediaWatch.livePosition(st.getPosition(), st.getLastPositionUpdateTime(),
                    SystemClock.elapsedRealtime(), st.getPlaybackSpeed(), playing);
            int pct = MediaWatch.percent(live, dur);
            if (pct < 0) return;

            EpisodeParser.Parsed p = EpisodeParser.parse(title);
            String series = p.title.isEmpty() ? title : p.title;
            int ep = p.ep < 0 ? 0 : p.ep;

            int before = Watched.percent(series, ep);
            boolean raised = Watched.record(series, ep, pct, "media");
            if (Progress.apply(series, ep, pct)) {
                Store.save();
                Store.notifyChanged();
                MxService s = MxService.get();
                if (s != null) s.refreshNotification();
            }
            if (raised) {
                Report.info("mediawatch", "watch " + series + " ep" + ep + " -> " + pct
                        + "% (" + live / 1000 + "/" + dur / 1000 + "s) "
                        + (MediaWatch.isFinished(live, dur) ? "FINISHED " : "")
                        + (playing ? "playing" : "paused")
                        + " title=" + title);
            }
            if (MediaWatch.isFinished(live, dur)
                    && !PlaybackTracker.isFinished(before)) {
                Report.info("mediawatch", "completed: " + series + " ep" + ep);
            }
        } catch (Throwable t) {
            Report.err("mediawatch", "sample failed", t);
        }
    }
}
