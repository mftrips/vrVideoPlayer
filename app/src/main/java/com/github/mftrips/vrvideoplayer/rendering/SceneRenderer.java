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

package com.github.mftrips.vrvideoplayer.rendering;

import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.opengl.GLES20;
import android.support.annotation.AnyThread;
import android.support.annotation.MainThread;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.mftrips.vrvideoplayer.rendering.Utils.checkGlError;

/**
 * Controls and renders the GL Scene.
 * <p>
 * <p>This class renders the display mesh and UI as required.
 */
public final class SceneRenderer {
    private static final String TAG = "SceneRenderer";
    private final AtomicBoolean frameAvailable = new AtomicBoolean();
    // This is the primary interface between the Media Player and the GL Scene.
    private SurfaceTexture displayTexture;

    // GL components for the mesh that display the media. displayMesh should only be accessed on the
    // GL Thread, but requestedDisplayMesh needs synchronization.
    @Nullable
    private Mesh displayMesh;
    @Nullable
    private Mesh requestedDisplayMesh;
    private int displayTexId;

    /**
     * Creates a SceneRenderer for VR but does not initialize it. {@link #glInit()} is used to
     * finish initializing the object on the GL thread.
     *
     * @return a SceneRender configured for VR.
     */
    @MainThread
    public static SceneRenderer createForVR() {
        return new SceneRenderer();
    }

    /**
     * Performs initialization on the GL thread. The scene isn't fully initialized until
     * glConfigureScene() completes successfully.
     */
    public void glInit() {
        checkGlError();

        // Set the background frame color. This is only visible if the display mesh isn't a full
        // sphere.
        GLES20.glClearColor(0.5f, 0.5f, 0.5f, 1.0f);
        checkGlError();

        // Create the texture used to render each frame of video.
        displayTexId = Utils.glCreateExternalTexture();
        displayTexture = new SurfaceTexture(displayTexId);
        checkGlError();

        // When the video decodes a new frame, tell the GL thread to update the image.
        displayTexture.setOnFrameAvailableListener(
                new OnFrameAvailableListener() {
                    @Override
                    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                        frameAvailable.set(true);
                    }
                });
    }

    /**
     * Creates the Surface & Mesh used by the MediaPlayer to render video.
     *
     * @param width  passed to {@link SurfaceTexture#setDefaultBufferSize(int, int)}
     * @param height passed to {@link SurfaceTexture#setDefaultBufferSize(int, int)}
     * @param mesh   {@link Mesh} used to display video
     * @return a Surface that can be passed to {@link android.media.MediaPlayer#setSurface(Surface)}
     */
    @AnyThread
    public synchronized @Nullable
    Surface createDisplay(int width, int height, Mesh mesh) {
        if (displayTexture == null) {
            Log.e(TAG, ".createDisplay called before GL Initialization completed.");
            return null;
        }

        requestedDisplayMesh = mesh;

        displayTexture.setDefaultBufferSize(width, height);
        return new Surface(displayTexture);
    }

    /**
     * Configures any late-initialized components.
     * <p>
     * <p>Since the creation of the Mesh can depend on disk access, this configuration needs to run
     * during each drawFrame to determine if the Mesh is ready yet. This also supports replacing an
     * existing mesh while the app is running.
     *
     * @return true if the scene is ready to be drawn
     */
    private synchronized boolean glConfigureScene() {
        if (displayMesh == null && requestedDisplayMesh == null) {
            // The scene isn't ready and we don't have enough information to configure it.
            return false;
        }

        // The scene is ready and we don't need to change it so we can glDraw it.
        if (requestedDisplayMesh == null) {
            return true;
        }

        // Configure or reconfigure the scene.
        if (displayMesh != null) {
            // Reconfiguration.
            displayMesh.glShutdown();
        }

        displayMesh = requestedDisplayMesh;
        requestedDisplayMesh = null;
        displayMesh.glInit(displayTexId);

        return true;
    }

    /**
     * Draws the scene with a given eye pose and type.
     *
     * @param viewProjectionMatrix 16 element GL matrix.
     * @param rotationOffsets      The offsets from a recenter() call.
     * @param eyeType              an {@link com.google.vr.sdk.base.Eye.Type} value
     */
    public void glDrawFrame(float[] viewProjectionMatrix, float[] rotationOffsets, int eyeType) {
        if (!glConfigureScene()) {
            // displayMesh isn't ready.
            return;
        }

        // glClear isn't strictly necessary when rendering fully spherical panoramas, but it can
        // improve performance on tiled renderers by causing the GPU to discard previous data.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        checkGlError();

        // The uiQuad uses alpha.
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glEnable(GLES20.GL_BLEND);

        if (frameAvailable.compareAndSet(true, false)) {
            displayTexture.updateTexImage();
            checkGlError();
        }

        if (displayMesh != null) {
            displayMesh.glDraw(viewProjectionMatrix, rotationOffsets, eyeType);
        }
    }

    /**
     * Cleans up the GL resources.
     */
    public void glShutdown() {
        if (displayMesh != null) {
            displayMesh.glShutdown();
        }
    }
}
