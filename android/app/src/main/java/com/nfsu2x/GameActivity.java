package com.nfsu2x;

import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

/** SDL surface plus a low-latency Android touch overlay. */
public final class GameActivity extends SDLActivity {
    public static final String EXTRA_GAME_DIR = "game_dir";
    public static final String EXTRA_RENDER_SCALE = "render_scale";
    public static final String EXTRA_VSYNC = "vsync";

    static {
        // SDLActivity normally loads libmain itself. Loading it here also
        // makes the JNI configuration calls available before SDL starts its
        // native main thread.
        System.loadLibrary("main");
    }

    private static native void nativeSetGameDirectory(String path);
    private static native void nativeSetRuntimeOptions(float scale, boolean vsync);
    public static native void nativeTouchState(int digital, byte[] analog,
                                               int lx, int ly, int rx, int ry);

    @Override
    protected void onCreate(Bundle state) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        String path = getIntent().getStringExtra(EXTRA_GAME_DIR);
        float scale = getIntent().getFloatExtra(EXTRA_RENDER_SCALE, 1.0f);
        boolean vsync = getIntent().getBooleanExtra(EXTRA_VSYNC, true);
        nativeSetGameDirectory(path);
        nativeSetRuntimeOptions(scale, vsync);

        super.onCreate(state);
        enterImmersive();
        addContentView(new TouchOverlay(this), new ViewGroupLayoutParams());
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus)
            enterImmersive();
    }

    @Override
    protected void onDestroy() {
        byte[] clear = new byte[8];
        nativeTouchState(0, clear, 0, 0, 0, 0);
        super.onDestroy();
    }

    /** Avoid an XML dependency for the full-screen overlay. */
    private static final class ViewGroupLayoutParams extends android.view.ViewGroup.LayoutParams {
        ViewGroupLayoutParams() {
            super(-1, -1);
        }
    }
}
