/*
 * T3rmux web terminal service. GPLv3, same license as the rest of this app.
 *
 * Runs ttyd bound to 127.0.0.1 only, protected by a random password, as a
 * foreground service with notification actions: Open, Copy password, Stop.
 * Never exposes the terminal beyond the phone itself.
 */
package com.termux.app.web;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;

public class WebTerminalService extends Service {

    public static final int PORT = 7681;
    public static final String USER = "t3";

    private static final String CHANNEL_ID = "t3_web_terminal";
    private static final int NOTIF_ID = 7681;
    private static final String ACT_START = ".WEB_START";
    private static final String ACT_STOP = ".WEB_STOP";
    private static final String ACT_COPY = ".WEB_COPY";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Process proc;
    private boolean stopping;

    // ------------------------------------------------------------------ paths
    // Termux layout: <files>/usr is the prefix, <files>/home is HOME.
    // Deriving from getFilesDir() keeps this correct for any package name.
    private static File prefix(Context c) { return new File(c.getFilesDir(), "usr"); }
    private static File home(Context c)   { return new File(c.getFilesDir(), "home"); }
    private static File state(Context c)  { return new File(home(c), ".t3-web"); }

    // ----------------------------------------------------------- lifecycle
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        String pkg = getPackageName();

        // Must call startForeground quickly after startForegroundService().
        startForeground(NOTIF_ID, buildNotification("Starting web terminal...", false));

        if (action != null && action.equals(pkg + ACT_STOP)) {
            stopServer();
            stopForeground(true);
            stopSelf();
        } else if (action != null && action.equals(pkg + ACT_COPY)) {
            copyPassword();
            updateNotification();
        } else {
            startServer();
        }
        return START_NOT_STICKY;   // never auto-restart: the user starts it on purpose
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    // -------------------------------------------------------------- server
    private synchronized void startServer() {
        if (proc != null && isAlive(proc)) {
            updateNotification();
            return;
        }
        stopping = false;
        installScripts(this);

        String password = randomToken();
        try {
            writeCredentials(USER + ":" + password);
        } catch (IOException e) {
            fail("Cannot write credentials: " + e.getMessage());
            return;
        }

        File launcher = new File(prefix(this), "bin/t3-webterm");
        ProcessBuilder pb = new ProcessBuilder(launcher.getAbsolutePath());
        pb.directory(home(this));
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(state(this), "ttyd.log"));

        // Clean environment (nothing secret in it; the password is read from a 0600 file).
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("HOME", home(this).getAbsolutePath());
        env.put("PREFIX", prefix(this).getAbsolutePath());
        env.put("PATH", new File(prefix(this), "bin").getAbsolutePath());
        env.put("TMPDIR", new File(prefix(this), "tmp").getAbsolutePath());
        env.put("LANG", "en_US.UTF-8");
        env.put("TERM", "xterm-256color");
        env.put("T3_WEB_PORT", String.valueOf(PORT));
        File preload = new File(prefix(this), "lib/libtermux-exec.so");
        if (preload.exists()) env.put("LD_PRELOAD", preload.getAbsolutePath());

        try {
            final Process p = pb.start();
            proc = p;
            new Thread(() -> {
                int code = -1;
                try { code = p.waitFor(); } catch (InterruptedException ignored) { }
                final int exit = code;
                main.post(() -> onServerExit(p, exit));
            }, "t3-web-wait").start();
            waitUntilListening();
        } catch (IOException e) {
            fail("Cannot start launcher: " + e.getMessage());
        }
    }

    private synchronized void stopServer() {
        stopping = true;
        if (proc != null) {
            proc.destroy();
            proc = null;
        }
        new File(state(this), "credentials").delete();   // do not leave a stale password
    }

    private void onServerExit(Process p, int code) {
        if (p != proc) return;              // an old process we already replaced
        proc = null;
        if (stopping) return;
        new File(state(this), "credentials").delete();
        String why = code == 127
                ? "ttyd missing. Run bundle-ttyd.sh, then start again."
                : "Stopped (exit " + code + "). See ~/.t3-web/ttyd.log";
        stopForeground(false);
        notifyPlain(why);
        stopSelf();
    }

    /** Poll 127.0.0.1:PORT for up to ~6s, then refresh the notification. */
    private void waitUntilListening() {
        new Thread(() -> {
            boolean up = false;
            for (int i = 0; i < 12 && !up; i++) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
                    up = true;
                } catch (IOException e) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                }
            }
            final boolean ok = up;
            main.post(() -> {
                if (ok) updateNotification();
                else notifyPlain("Not listening yet. Check ~/.t3-web/ttyd.log");
            });
        }, "t3-web-ready").start();
    }

    private void fail(String msg) {
        stopForeground(false);
        notifyPlain(msg);
        stopSelf();
    }

    // ---------------------------------------------------------- credentials
    private void writeCredentials(String line) throws IOException {
        File dir = state(this);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir " + dir);
        File f = new File(dir, "credentials");
        if (!f.exists() && !f.createNewFile()) throw new IOException("create " + f);
        // Owner-only, set BEFORE the secret is written.
        f.setReadable(false, false);
        f.setWritable(false, false);
        f.setReadable(true, true);
        f.setWritable(true, true);
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readPassword() {
        File f = new File(state(this), "credentials");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            String s = new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8).trim();
            int i = s.indexOf(':');
            return i >= 0 ? s.substring(i + 1) : s;
        } catch (IOException e) {
            return null;
        }
    }

    private void copyPassword() {
        String pw = readPassword();
        if (pw == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("T3 web terminal password", pw));
            Toast.makeText(this, "Password copied. Username: " + USER, Toast.LENGTH_LONG).show();
        }
    }

    private static String randomToken() {
        byte[] b = new byte[24];                       // 192 bits
        new SecureRandom().nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    // ---------------------------------------------------------- notification
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Web terminal", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private PendingIntent serviceAction(String suffix, int code) {
        Intent i = new Intent(this, WebTerminalService.class).setAction(getPackageName() + suffix);
        return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT)
                : PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification(String text, boolean withActions) {
        ensureChannel();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        // Swap for R.drawable.ic_service_notification once you want the Termux-style icon.
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Web terminal")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (withActions) {
            Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + PORT + "/"));
            PendingIntent openPi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(android.R.drawable.ic_menu_view, "Open in browser", openPi);
            b.addAction(android.R.drawable.ic_menu_edit, "Copy password", serviceAction(ACT_COPY, 2));
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", serviceAction(ACT_STOP, 3));
        }
        return b.build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(
                    "Listening on 127.0.0.1:" + PORT + "  (user: " + USER + ")", true));
        }
    }

    private void notifyPlain(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Web terminal")
                    .setContentText(text)
                    .setAutoCancel(true);
            nm.notify(NOTIF_ID, b.build());
        }
    }

    private static boolean isAlive(Process p) {
        try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    // ------------------------------------------------- script/asset install
    /**
     * Copies t3-webterm and t3-web from assets into $PREFIX/bin, filling in the real
     * prefix and package name. Also stages ttyd-t3rmux.tar.gz (if you bundled one).
     * Call this from TermuxActivity.onCreate() if you want `t3-web` available
     * before the first start.
     */
    public static void installScripts(Context ctx) {
        File bin = new File(prefix(ctx), "bin");
        File st = state(ctx);
        //noinspection ResultOfMethodCallIgnored
        bin.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        st.mkdirs();
        for (String name : new String[]{"t3-webterm", "t3-web"}) {
            try {
                String txt = readAsset(ctx, name)
                        .replace("@PREFIX@", prefix(ctx).getAbsolutePath())
                        .replace("@APPID@", ctx.getPackageName())
                        .replace("@SERVICE@", WebTerminalService.class.getName());
                File out = new File(bin, name);
                try (FileOutputStream fo = new FileOutputStream(out, false)) {
                    fo.write(txt.getBytes(StandardCharsets.UTF_8));
                }
                out.setExecutable(true, true);
                out.setReadable(true, true);
            } catch (IOException ignored) {
                // asset missing: apply.sh not fully run
            }
        }
        try (InputStream in = ctx.getAssets().open("ttyd-t3rmux.tar.gz");
             FileOutputStream fo = new FileOutputStream(new File(st, "ttyd-t3rmux.tar.gz"), false)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        } catch (IOException ignored) {
            // no bundled ttyd archive; fine if ttyd is already in the prefix
        }
    }

    private static String readAsset(Context ctx, String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Convenience for other code (menu item, shortcut, etc.). */
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, WebTerminalService.class)
                .setAction(ctx.getPackageName() + ACT_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }
}
