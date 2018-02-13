/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.mftrips.vrvideoplayer;

import android.Manifest.permission;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.support.annotation.MainThread;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.google.vr.sdk.base.Eye;
import com.google.vr.sdk.base.GvrActivity;
import com.google.vr.sdk.base.GvrView;
import com.google.vr.sdk.base.HeadTransform;
import com.google.vr.sdk.base.Viewport;
import com.github.mftrips.vrvideoplayer.rendering.SceneRenderer;

import javax.microedition.khronos.egl.EGLConfig;

/**
 * GVR Activity demonstrating a 360 video player.
 * <p>
 * <p> The default intent for this Activity will load a 360 placeholder panorama. For more options
 * on how to load other media using a custom Intent, see {@link MediaLoader}.
 */
public class MainActivity extends GvrActivity {
    private static final int READ_EXTERNAL_STORAGE_PERMISSION_ID = 1;
    private static final int EXIT_FROM_VR_REQUEST_CODE = 42;
    //https://stackoverflow.com/a/11679788
    final Handler handler = new Handler();
    private GvrView gvrView;
    Runnable longPressed = new Runnable() {
        public void run() {
            recenter();
        }
    };
    private SyncListener syncListener;
    // Given an intent with a media file and format, this will load the file and generate the mesh.
    private MediaLoader mediaLoader;
    private boolean isRecreating = false;

    /**
     * Configures the VR system.
     *
     * @param savedInstanceState unused in this sample but it could be used to track video position
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        isRecreating = false;
        syncListener = new SyncListener();
        mediaLoader = new MediaLoader(this);

        gvrView = new GvrView(this);

        // Since the videos have fewer pixels per degree than the phones, reducing the render target
        // scaling factor reduces the work required to render the scene. This factor can be
        // adjusted at
        // runtime depending on the resolution of the loaded video.
        // You can use Eye.getViewport() in the overridden onDrawEye() method to determine the
        // current
        // render target size in pixels.
        gvrView.setRenderTargetScale(.5f);

        // Standard GvrView configuration
        Renderer renderer = new Renderer();
        gvrView.setEGLConfigChooser(
                8, 8, 8, 8,  // RGBA bits.
                16,  // Depth bits.
                0);  // Stencil bits.
        gvrView.setRenderer(renderer);
        setContentView(gvrView);

        if (ContextCompat.checkSelfPermission(this, permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    MainActivity.this,
                    new String[]{permission.READ_EXTERNAL_STORAGE},
                    READ_EXTERNAL_STORAGE_PERMISSION_ID
            );
        }
        initializeActivity();
    }

    /**
     * Normal apps don't need this. However, since we use adb to interact with this sample, we
     * want any new adb Intents to be routed to the existing Activity rather than launching a new
     * Activity.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        // Save the new Intent which may contain a new Uri. Then tear down & recreate this Activity
        // to load that Uri.
        setIntent(intent);
        isRecreating = true;
        recreate();
    }

    /**
     * Initializes the Activity only if the permission has been granted.
     */
    private void initializeActivity() {
        mediaLoader.handleIntent(getIntent(), syncListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mediaLoader.resume();
        syncListener.update();
    }

    @Override
    protected void onPause() {
        mediaLoader.pause();
        syncListener.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mediaLoader.destroy();
        onActivityResult(EXIT_FROM_VR_REQUEST_CODE, RESULT_OK, null);
        super.onDestroy();
        if (!isRecreating) {
            finish();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
            recenter();
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                MediaLoader.State state = mediaLoader.playPause();
                if (state == MediaLoader.State.PLAYING) {
                    syncListener.update();
                } else {
                    syncListener.pause();
                }
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mediaLoader.seek(MediaLoader.Direction.BACKWARD, MediaLoader.Magnitude.LARGE);
                syncListener.update();
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_NEXT) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mediaLoader.seek(MediaLoader.Direction.FORWARD, MediaLoader.Magnitude.LARGE);
                syncListener.update();
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_REWIND) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mediaLoader.seek(MediaLoader.Direction.BACKWARD, MediaLoader.Magnitude.SMALL);
                syncListener.update();
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                onDestroy();
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mediaLoader.seek(MediaLoader.Direction.FORWARD, MediaLoader.Magnitude.SMALL);
                syncListener.update();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            handler.postDelayed(longPressed, ViewConfiguration.getLongPressTimeout());
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            handler.removeCallbacks(longPressed);
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (event.getHistorySize() > 0) {
                double dx = event.getX(0) - event.getHistoricalX(0, 0);
                double dy = event.getY(0) - event.getHistoricalY(0, 0);
                double distance = Math.sqrt(Math.pow(dx, 2) + Math.pow(dy, 2));
                if (distance > 5) {
                    handler.removeCallbacks(longPressed);
                }
            }
        }
        super.dispatchTouchEvent(event);
        return false;
    }

    void recenter() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            vibrator.vibrate(50);
        }
        gvrView.recenterHeadTracker();
    }

    /**
     * Standard GVR renderer. Most of the real work is done by {@link SceneRenderer}.
     */
    private class Renderer implements GvrView.StereoRenderer {
        private static final float Z_NEAR = .1f;
        private static final float Z_FAR = 100;

        final SceneRenderer scene;

        private final float[] viewProjectionMatrix = new float[16];

        /**
         * Creates the Renderer and configures the VR exit button.
         */
        @MainThread
        Renderer() {
            scene = SceneRenderer.createForVR();
        }

        @Override
        public void onNewFrame(HeadTransform headTransform) {
        }

        @Override
        public void onDrawEye(Eye eye) {
            Matrix.multiplyMM(viewProjectionMatrix, 0, eye.getPerspective(Z_NEAR, Z_FAR), 0, eye
                    .getEyeView(), 0);
            scene.glDrawFrame(viewProjectionMatrix, eye.getType());
        }

        @Override
        public void onFinishFrame(Viewport viewport) {
        }

        @Override
        public void onSurfaceCreated(EGLConfig config) {
            scene.glInit();
            mediaLoader.onGlSceneReady(scene);
        }

        @Override
        public void onSurfaceChanged(int width, int height) {
        }

        @Override
        public void onRendererShutdown() {
            scene.glShutdown();
        }
    }
}
