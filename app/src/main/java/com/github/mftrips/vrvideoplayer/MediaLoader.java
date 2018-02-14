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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.AnyThread;
import android.support.annotation.MainThread;
import android.util.Log;
import android.view.Surface;

import com.github.mftrips.vrvideoplayer.rendering.Mesh;
import com.github.mftrips.vrvideoplayer.rendering.SceneRenderer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLConnection;
import java.security.InvalidParameterException;

/**
 * MediaLoader takes an Intent from the user and loads the specified media file.
 * <p>
 * <p>The process to load media requires multiple threads since the media is read from disk on a
 * background thread, but it needs to be loaded into the GL scene only after GL initialization is
 * complete.
 * <p>
 * <p>The Intent used to launch the app is parsed by this class and the extra & data fields are
 * extracted. The data field should have a URI useable by {@link MediaPlayer} or
 * {@link BitmapFactory}. There should also be an integer extra matching one of the MEDIA_* types in
 * {@link Mesh}.
 */
class MediaLoader {
    private static final String TAG = "MediaLoader";

    private static final String MEDIA_FORMAT_KEY = "stereoFormat";
    private static final int DEFAULT_SURFACE_HEIGHT_PX = 2048;

    /**
     * A spherical mesh for video should be large enough that there are no stereo artifacts.
     */
    private static final int SPHERE_RADIUS_METERS = 50;

    /**
     * These should be configured based on the video type. But this sample assumes 360 video.
     */
    private static final int DEFAULT_SPHERE_VERTICAL_DEGREES = 180;
    private static final int DEFAULT_SPHERE_HORIZONTAL_DEGREES = 360;

    /**
     * The 360 x 180 sphere has 15 degree quads. Increase these if lines in your video look wavy.
     */
    private static final int DEFAULT_SPHERE_ROWS = 12;
    private static final int DEFAULT_SPHERE_COLUMNS = 24;
    private final Context context;
    // This should be set or cleared in a synchronized manner.
    private MediaPlayer mediaPlayer;
    // Supports for loading images.
    private Bitmap mediaImage;
    // If the video or image fails to load, a placeholder panorama is rendered with error text.
    private String errorText;
    private SyncListener syncListener;
    // Due to the slow loading media times, it's possible to tear down the app before mediaPlayer is
    // ready. In that case, abandon all the pending work.
    // This should be set or cleared in a synchronized manner.
    private boolean isDestroyed = false;
    // The type of mesh created depends on the type of media.
    private Mesh mesh;
    // The sceneRenderer is set after GL initialization is complete.
    private SceneRenderer sceneRenderer;
    // The displaySurface is configured after both GL initialization and media loading.
    private Surface displaySurface;

    MediaLoader(Context context) {
        this.context = context;
    }

    /**
     * Renders a placeholder grid with optional error text.
     */
    private static void renderEquirectangularGrid(Canvas canvas, String message) {
        // Configure the grid. Each square will be 15 x 15 degrees.
        final int width = canvas.getWidth();
        final int height = canvas.getHeight();
        // This assumes a 4k resolution.
        final int majorWidth = width / 256;
        final int minorWidth = width / 1024;
        final Paint paint = new Paint();

        // Draw a black ground & gray sky background
        paint.setColor(Color.BLACK);
        canvas.drawRect(0, height / 2, width, height, paint);
        paint.setColor(Color.GRAY);
        canvas.drawRect(0, 0, width, height / 2, paint);

        // Render the grid lines.
        paint.setColor(Color.WHITE);

        for (int i = 0; i < DEFAULT_SPHERE_COLUMNS; ++i) {
            int x = width * i / DEFAULT_SPHERE_COLUMNS;
            paint.setStrokeWidth((i % 3 == 0) ? majorWidth : minorWidth);
            canvas.drawLine(x, 0, x, height, paint);
        }

        for (int i = 0; i < DEFAULT_SPHERE_ROWS; ++i) {
            int y = height * i / DEFAULT_SPHERE_ROWS;
            paint.setStrokeWidth((i % 3 == 0) ? majorWidth : minorWidth);
            canvas.drawLine(0, y, width, y, paint);
        }

        // Render optional text.
        if (message != null) {
            paint.setTextSize(height / 64);
            paint.setColor(Color.RED);
            float textWidth = paint.measureText(message);

            canvas.drawText(
                    message,
                    width / 2 - textWidth / 2, // Horizontally center the text.
                    9 * height / 16, // Place it slightly below the horizon for better contrast.
                    paint);
        }
    }

    /**
     * Loads custom videos based on the Intent or load the default video. See the Javadoc for this
     * class for information on generating a custom intent via adb.
     */
    void handleIntent(Intent intent, SyncListener syncListener) {
        this.syncListener = syncListener;

        // Load the bitmap in a background thread to avoid blocking the UI thread. This operation
        // can take 100s of milliseconds.
        // Note that this sample doesn't cancel any pending mediaLoaderTasks since it assumes only
        // one Intent will ever be fired for a single Activity lifecycle.
        MediaLoaderTask mediaLoaderTask = new MediaLoaderTask();
        mediaLoaderTask.execute(intent);
    }

    /**
     * Notifies MediaLoader that GL components have initialized.
     */
    void onGlSceneReady(SceneRenderer sceneRenderer) {
        this.sceneRenderer = sceneRenderer;
        displayWhenReady();
    }

    /**
     * Creates the 3D scene and load the media after sceneRenderer & mediaPlayer are ready. This can
     * run on the GL Thread or a background thread.
     */
    @AnyThread
    private synchronized void displayWhenReady() {
        if (isDestroyed) {
            // This only happens when the Activity is destroyed immediately after creation.
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
            return;
        }

        if (displaySurface != null) {
            // Avoid double initialization caused by sceneRenderer & mediaPlayer being initialized
            // before displayWhenReady is executed.
            return;
        }

        if ((errorText == null && mediaImage == null && mediaPlayer == null)
                || sceneRenderer == null) {
            // Wait for everything to be initialized.
            return;
        }

        // The important methods here are the setSurface & lockCanvas calls. These will have to
        // happen after the GLView is created.
        if (mediaPlayer != null) {
            // For videos, attach the displaySurface and mediaPlayer.
            displaySurface = sceneRenderer.createDisplay(
                    mediaPlayer.getVideoWidth(), mediaPlayer.getVideoHeight(), mesh);
            mediaPlayer.setSurface(displaySurface);
            // Start playback.
            mediaPlayer.setLooping(true);
            mediaPlayer.start();
        } else if (mediaImage != null) {
            // For images, acquire the displaySurface and draw the bitmap to it. Since our Mesh
            // class uses an GL_TEXTURE_EXTERNAL_OES texture, it's possible to perform this decoding
            // and rendering of a bitmap in the background without stalling the GL thread. If the
            // Mesh used a standard GL_TEXTURE_2D, then it's possible to stall the GL thread for
            // 100+ ms during the glTexImage2D call when loading 4k x 4k panoramas and copying the
            // bitmap's data.
            displaySurface = sceneRenderer.createDisplay(
                    mediaImage.getWidth(), mediaImage.getHeight(), mesh);
            if (displaySurface != null) {
                Canvas c = displaySurface.lockCanvas(null);
                c.drawBitmap(mediaImage, 0, 0, null);
                displaySurface.unlockCanvasAndPost(c);
            }
        } else {
            // Handle the error case by creating a placeholder panorama.
            mesh = Mesh.createUvSphere(
                    SPHERE_RADIUS_METERS, DEFAULT_SPHERE_ROWS, DEFAULT_SPHERE_COLUMNS,
                    DEFAULT_SPHERE_VERTICAL_DEGREES, DEFAULT_SPHERE_HORIZONTAL_DEGREES,
                    Mesh.MEDIA_MONOSCOPIC);

            // 4k x 2k is a good default resolution for monoscopic panoramas.
            displaySurface = sceneRenderer.createDisplay(
                    2 * DEFAULT_SURFACE_HEIGHT_PX, DEFAULT_SURFACE_HEIGHT_PX, mesh);
            if (displaySurface != null) {
                // Render placeholder grid and error text.
                Canvas c = displaySurface.lockCanvas(null);
                renderEquirectangularGrid(c, errorText);
                displaySurface.unlockCanvasAndPost(c);
            }
        }
    }

    @MainThread
    synchronized void pause() {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
        }
    }

    @MainThread
    synchronized void resume() {
        if (mediaPlayer != null) {
            mediaPlayer.start();
        }
    }

    @MainThread
    synchronized State playPause() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                return State.PAUSED;
            } else {
                mediaPlayer.start();
                return State.PLAYING;
            }
        }
        return State.PAUSED;
    }

    @MainThread
    synchronized void seek(Direction direction, Magnitude magnitude) {
        if (mediaPlayer != null) {
            int currentPosition = mediaPlayer.getCurrentPosition();
            int delta;
            if (magnitude == Magnitude.SMALL) {
                delta = 15 * 1000;
            } else {
                delta = 60 * 1000;
            }
            if (direction == Direction.FORWARD) {
                mediaPlayer.seekTo(currentPosition + delta);
            } else {
                mediaPlayer.seekTo(currentPosition - delta);
            }
        }
    }

    /**
     * Tears down MediaLoader and prevents further work from happening.
     */
    @MainThread
    synchronized void destroy() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.reset();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        syncListener.destroy();
        isDestroyed = true;
    }

    enum State {
        PLAYING,
        PAUSED
    }

    enum Direction {
        FORWARD,
        BACKWARD
    }

    enum Magnitude {
        SMALL,
        LARGE
    }

    /**
     * Helper class to media loading. This accesses the disk and decodes images so it needs to run
     * in the background.
     */
    @SuppressLint("StaticFieldLeak")
    private class MediaLoaderTask extends AsyncTask<Intent, Void, Void> {
        @Override
        protected Void doInBackground(Intent... intent) {
            if (intent == null || intent.length < 1 || intent[0] == null
                    || intent[0].getData() == null) {
                // This happens if the Activity wasn't started with the right intent.
                errorText = "No media loaded.";
                Log.e(TAG, errorText);
                return null;
            }

            // Extract the stereoFormat from the Intent's extras.
            int stereoFormat = intent[0].getIntExtra(MEDIA_FORMAT_KEY, Mesh.MEDIA_MONOSCOPIC);
            if (stereoFormat != Mesh.MEDIA_STEREO_LEFT_RIGHT
                    && stereoFormat != Mesh.MEDIA_STEREO_TOP_BOTTOM) {
                stereoFormat = Mesh.MEDIA_MONOSCOPIC;
            }

            mesh = Mesh.createUvSphere(
                    SPHERE_RADIUS_METERS, DEFAULT_SPHERE_ROWS, DEFAULT_SPHERE_COLUMNS,
                    DEFAULT_SPHERE_VERTICAL_DEGREES, DEFAULT_SPHERE_HORIZONTAL_DEGREES,
                    stereoFormat);

            // Based on the Intent's data, load the appropriate media from disk.
            Uri uri = intent[0].getData();
            try {
                syncListener.setFilename(uri.getLastPathSegment());
                File file = new File(uri.getPath());
                if (!file.exists()) {
                    throw new FileNotFoundException();
                }

                String type = URLConnection.guessContentTypeFromName(uri.getPath());
                if (type == null) {
                    throw new InvalidParameterException("Unknown file type: " + uri);
                } else if (type.startsWith("image")) {
                    // Decoding a large image can take 100+ ms.
                    mediaImage = BitmapFactory.decodeFile(uri.getPath());
                } else if (type.startsWith("video")) {
                    MediaPlayer mp = MediaPlayer.create(context, uri);
                    synchronized (MediaLoader.this) {
                        // This needs to be synchronized with the methods that could clear
                        // mediaPlayer.
                        mediaPlayer = mp;
                    }
                } else {
                    throw new InvalidParameterException("Unsupported MIME type: " + type);
                }

            } catch (IOException | InvalidParameterException e) {
                errorText = String.format("Error loading file [%s]: %s", uri.getPath(), e);
                Log.e(TAG, errorText);
            }

            displayWhenReady();
            return null;
        }

        @Override
        public void onPostExecute(Void unused) {
            syncListener.setMediaPlayer(mediaPlayer);
        }
    }
}
