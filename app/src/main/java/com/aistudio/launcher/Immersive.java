package com.aistudio.launcher;

import android.os.Build;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

/** Immersive-mode helpers: hide the status bar until the user swipes it back. */
final class Immersive {

    private Immersive() {
    }

    @SuppressWarnings("deprecation")
    static void apply(AppCompatActivity activity) {
        Window window = activity.getWindow();
        // Force the decor view to exist first: on API 30+ getInsetsController()
        // dereferences the internal decor view and NPEs if setContentView() has
        // not run yet (e.g. when called at the very top of onCreate).
        window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController c = window.getInsetsController();
            if (c != null) {
                // Hide both bars so full-screen content (e.g. the VNC login window)
                // is not obscured by the phone's bottom navigation bar.
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                // Reveal either bar transiently (swipe from edge) and auto-hide after.
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
