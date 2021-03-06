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

import org.metafetish.buttplug.core.ButtplugEvent;
import org.metafetish.buttplug.core.ButtplugEventHandler;

import javax.microedition.khronos.egl.EGLConfig;

/**
 * GVR Activity demonstrating a 360 video player.
 * <p>
 * <p> The default intent for this Activity will load a 360 placeholder panorama. For more options
 * on how to load other media using a custom Intent, see {@link MediaLoader}.
 */
public class CardboardActivity extends GvrActivity {
    private static final int READ_EXTERNAL_STORAGE_PERMISSION_ID = 1;
    private static final int EXIT_FROM_VR_REQUEST_CODE = 42;
    //https://stackoverflow.com/a/11679788
    final Handler handler = new Handler();
    Runnable longPressed = new Runnable() {
        public void run() {
            recenter();
        }
    };
    protected ButtplugApplication application;
    protected SyncListener syncListener;
    // Given an intent with a media file and format, this will load the file and generate the mesh.
    protected MediaLoader mediaLoader;
    private boolean isRecreating = false;
    private boolean isRecentering = false;
    protected float[] rotationOffsets = {0f, 0f, 0f};
    protected float[] lastRotationOffsets = {0f, 0f, 0f};

    protected ButtplugEventHandler scanHandler = new ButtplugEventHandler();
    public ButtplugEventHandler getScanHandler() {
        return this.scanHandler;
    }

    /**
     * Configures the VR system.
     *
     * @param savedInstanceState unused in this sample but it could be used to track video position
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.application = (ButtplugApplication) this.getApplication();
        this.rotationOffsets[0] = application.getVerticalOffset();
        this.application.initialize(this);
        HapticsManager hapticsManager = application.getHapticsManager();
        this.isRecreating = false;
        this.syncListener = new SyncListener();
        this.mediaLoader = new MediaLoader(this, this.syncListener, hapticsManager);

        GvrView gvrView = new GvrView(this);

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
                    CardboardActivity.this,
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
        mediaLoader.handleIntent(getIntent());
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
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                recenter();
            }
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_START) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                MediaLoader.State state = mediaLoader.playPause();
                if (state == MediaLoader.State.PLAYING) {
                    syncListener.update();
                } else {
                    syncListener.pause();
                }
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PREVIOUS || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mediaLoader.seek(MediaLoader.Direction.BACKWARD, MediaLoader.Magnitude.LARGE);
                syncListener.update();
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_NEXT || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mediaLoader.seek(MediaLoader.Direction.FORWARD, MediaLoader.Magnitude.LARGE);
                syncListener.update();
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_REWIND || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mediaLoader.seek(MediaLoader.Direction.BACKWARD, MediaLoader.Magnitude.SMALL);
                syncListener.update();
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mediaLoader.seek(MediaLoader.Direction.FORWARD, MediaLoader.Magnitude.SMALL);
                syncListener.update();
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                onDestroy();
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L1) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mediaLoader.previous();
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_R1) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mediaLoader.next();
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_X) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mediaLoader.smallBack();
                syncListener.update();
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_Y) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                this.scanHandler.invoke(new ButtplugEvent());
            }
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER) {
            return true;
        } else //noinspection RedundantIfStatement
            if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
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

    protected void recenter() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            vibrator.vibrate(50);
        }
        isRecentering = true;
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
            if (CardboardActivity.this.isRecentering) {
                // TODO: Derive from quaternions to avoid gimbal lock?
                // https://en.wikipedia.org/wiki/Conversion_between_quaternions_and_Euler_angles#Quaternion_to_Euler_Angles_Conversion
                headTransform.getEulerAngles(CardboardActivity.this.rotationOffsets, 0);
                CardboardActivity.this.application.setVerticalOffset(CardboardActivity.this.rotationOffsets[0]);
                CardboardActivity.this.isRecentering = false;
            }
            headTransform.getEulerAngles(CardboardActivity.this.lastRotationOffsets, 0);
        }

        @Override
        public void onDrawEye(Eye eye) {
            Matrix.multiplyMM(viewProjectionMatrix, 0, eye.getPerspective(Z_NEAR, Z_FAR), 0, eye
                    .getEyeView(), 0);
            scene.glDrawFrame(viewProjectionMatrix, rotationOffsets, eye.getType());
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
