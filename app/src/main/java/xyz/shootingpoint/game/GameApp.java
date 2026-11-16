package xyz.shootingpoint.game;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** Keeps the last crash in a file, so the next start can show it: this app reaches phones with no cable and no log access. */
public class GameApp extends Application {
    static final String CRASH_FILE = "last-crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler system = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try (FileOutputStream file = new FileOutputStream(new File(getFilesDir(), CRASH_FILE));
                 PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(file, StandardCharsets.UTF_8))) {
                error.printStackTrace(out);
            } catch (Exception e) {
                Log.w("GameApp", "could not save the crash", e);
            }
            if (system != null) system.uncaughtException(thread, error);
        });
    }
}
