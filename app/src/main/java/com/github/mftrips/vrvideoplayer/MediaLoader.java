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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.annotation.AnyThread;
import android.support.annotation.MainThread;
import android.util.Log;
import android.view.Surface;
import android.webkit.MimeTypeMap;

import com.github.mftrips.vrvideoplayer.rendering.Mesh;
import com.github.mftrips.vrvideoplayer.rendering.SceneRenderer;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MediaLoader takes an Intent from the user and loads the specified media file.
 * <p>
 * <p>The process to load media requires multiple threads since the media is read from disk on a
 * background thread, but it needs to be loaded into the GL scene only after GL initialization is
 * complete.
 * <p>
 * <p>The Intent used to launch the app is parsed by this class and the extra & data fields are
 * extracted. The data field should have a URI useable by {@link MediaPlayer}.
 */
class MediaLoader {
    private static final String TAG = "MediaLoader";

    private static final int DEFAULT_SURFACE_WIDTH_PX = 2048;
    private static final int DEFAULT_SURFACE_HEIGHT_PX = 2048;

    /**
     * A spherical mesh for video should be large enough that there are no stereo artifacts.
     */
    private static final int SPHERE_RADIUS_METERS = 50;

    /**
     * These should be configured based on the video type. But this app assumes 180 degree video.
     */
    private static final int DEFAULT_SPHERE_VERTICAL_DEGREES = 180;
    private static final int DEFAULT_SPHERE_HORIZONTAL_DEGREES = 180;

    /**
     * The 180 x 180 sphere has 15 degree quads. Increase these if lines in your video look wavy.
     */
    private static final int DEFAULT_SPHERE_ROWS = 12;
    private static final int DEFAULT_SPHERE_COLUMNS = 12;
    private final Context context;
    // This should be set or cleared in a synchronized manner.
    private MediaPlayer mediaPlayer;
    // If the video fails to load, a placeholder panorama is rendered with error text.
    private String errorText;
    // Monitors MediaPlayer and updates ScriptPlayer's timecode client.
    private SyncListener syncListener;
    private HapticsManager hapticsManager;
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
    private Uri lastUri;
    private File lastPath = new File(Environment.getExternalStorageDirectory(), "vrVideos");

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

    MediaLoader(Context context, SyncListener syncListener, HapticsManager hapticsManager) {
        this.context = context;
        this.syncListener = syncListener;
        this.hapticsManager = hapticsManager;
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
    void handleIntent(Intent intent) {
        // Load the bitmap in a background thread to avoid blocking the UI thread. This operation
        // can take 100s of milliseconds.
        // Note that this sample doesn't cancel any pending mediaLoaderTasks since it assumes only
        // one Intent will ever be fired for a single Activity lifecycle.
        MediaLoaderFromIntentTask mediaLoaderTask = new MediaLoaderFromIntentTask();
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

        if ((errorText == null && mediaPlayer == null) || sceneRenderer == null) {
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
            mediaPlayer.setLooping(false);
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mediaPlayer) {
                    mediaPlayer.start();
                    MediaLoader.this.hapticsManager.onSeek();
                }
            });
            mediaPlayer.start();
        } else {
            // Handle the error case by creating a placeholder panorama.
            mesh = Mesh.createUvSphere(
                    SPHERE_RADIUS_METERS, DEFAULT_SPHERE_ROWS, DEFAULT_SPHERE_COLUMNS,
                    DEFAULT_SPHERE_VERTICAL_DEGREES, DEFAULT_SPHERE_HORIZONTAL_DEGREES,
                    Mesh.MEDIA_MONOSCOPIC);

            // 2k x 2k is a good default resolution for monoscopic panoramas.
            displaySurface = sceneRenderer.createDisplay(
                    DEFAULT_SURFACE_WIDTH_PX, DEFAULT_SURFACE_HEIGHT_PX, mesh);
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
            this.hapticsManager.onPause();
        }
    }

    @MainThread
    synchronized void resume() {
        if (mediaPlayer != null) {
            mediaPlayer.start();
            this.hapticsManager.onPlay();
        }
    }

    @MainThread
    synchronized State playPause() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                this.hapticsManager.onPause();
                return State.PAUSED;
            } else {
                mediaPlayer.start();
                this.hapticsManager.onPlay();
                return State.PLAYING;
            }
        }
        this.hapticsManager.onPause();
        return State.PAUSED;
    }

    @MainThread
    synchronized void smallBack() {
        if (mediaPlayer != null) {
            int currentPosition = mediaPlayer.getCurrentPosition();
            mediaPlayer.seekTo(currentPosition - 7 * 1000);
            this.hapticsManager.onSeek();
        }
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
            this.hapticsManager.onSeek();
        }
    }

    public void next() {
        MediaLoaderFromLastPathTask mediaLoaderTask = new MediaLoaderFromLastPathTask();
        mediaLoaderTask.execute(Direction.FORWARD);
    }

    public void previous() {
        MediaLoaderFromLastPathTask mediaLoaderTask = new MediaLoaderFromLastPathTask();
        mediaLoaderTask.execute(Direction.BACKWARD);
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
        this.hapticsManager.onPause();
        isDestroyed = true;
    }

    /**
     * Helper class to media loading. This accesses the disk and decodes images so it needs to run
     * in the background.
     */
    @SuppressLint("StaticFieldLeak")
    private class MediaLoaderFromIntentTask extends AsyncTask<Intent, Void, Void> {
        @Override
        protected Void doInBackground(Intent... intent) {
            if (intent == null || intent.length < 1 || intent[0] == null
                    || intent[0].getData() == null) {
                // This happens if the Activity wasn't started with the right intent.
                errorText = "No media loaded.";
                Log.e(TAG, errorText);
                return null;
            }

            mesh = Mesh.createUvSphere(
                    SPHERE_RADIUS_METERS, DEFAULT_SPHERE_ROWS, DEFAULT_SPHERE_COLUMNS,
                    DEFAULT_SPHERE_VERTICAL_DEGREES, DEFAULT_SPHERE_HORIZONTAL_DEGREES,
                    Mesh.MEDIA_STEREOSCOPIC);

            // Based on the Intent's data, load the appropriate media from disk.
            Uri uri = intent[0].getData();
            MediaLoader.this.lastUri = uri;
            MediaLoader.this.lastPath = null;
            MediaPlayer mp = MediaPlayer.create(context, uri);
            if (mp != null) {
                File file = new File(uri.getPath());
                if (file.exists()) {
                    MediaLoader.this.lastPath = file;
                }
                synchronized (MediaLoader.this) {
                    // This needs to be synchronized with the methods that could clear mediaPlayer.
                    mediaPlayer = mp;
                }
            } else {
                errorText = String.format("Error loading %s", Uri.decode(uri.toString()));
                Log.e(TAG, errorText);
            }

            displayWhenReady();
            return null;
        }

        @Override
        public void onPostExecute(Void unused) {
            syncListener.setMediaPlayer(mediaPlayer);
            if (MediaLoader.this.lastPath != null) {
                MediaLoader.this.hapticsManager.onMediaLoaded(MediaLoader.this.lastPath, MediaLoader.this.mediaPlayer);
            }
            if (MediaLoader.this.lastUri != null) {
                Uri uri = MediaLoader.this.lastUri;
                MediaLoader.this.syncListener.setFilename(uri.getScheme() + "://" + uri.getLastPathSegment());
            }
        }
    }

    /**
     * Helper class to media loading. This accesses the disk and decodes images so it needs to run
     * in the background.
     */
    @SuppressLint("StaticFieldLeak")
    private class MediaLoaderFromLastPathTask extends AsyncTask<Direction, Void, Void> {

        class VideoFilter implements FilenameFilter {

            @Override
            public boolean accept(File file, String s) {
                if (!file.canRead()) {
                    return false;
                }
                String extension = MimeTypeMap.getFileExtensionFromUrl(s);
                if (extension != null) {
                    String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    if (mimeType != null) {
                        //noinspection RedundantIfStatement
                        if (mimeType.matches("video/(mp4|webm)")) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }

        @Override
        protected Void doInBackground(Direction... directions) {
            Direction direction = directions[0];
            if (direction == null) {
                Log.e(TAG, "No direction provided");
                return null;
            }
            if (!MediaLoader.this.lastPath.exists()) {
                Log.e(TAG, "Last path does not exist");
                return null;
            }
            File parent;
            if (MediaLoader.this.lastPath.isDirectory()) {
                parent = MediaLoader.this.lastPath;
            } else {
                parent = MediaLoader.this.lastPath.getParentFile();
            }
            if (!parent.canRead()) {
                Log.e(TAG, "Unable to read from path");
                return null;
            }
            File[] files = parent.listFiles(new VideoFilter());
            if (files == null) {
                Log.e(TAG, "Failed to retrieve available files");
                return null;
            } else if (files.length == 0) {
                Log.e(TAG, "No available files");
                return null;
            }
            File file;
            if (MediaLoader.this.lastPath.isDirectory()) {
                if (direction == Direction.FORWARD) {
                    file = files[0];
                } else {
                    file = files[files.length - 1];
                }
            } else {
                if (files.length == 1) {
                    Log.e(TAG, "No additional files available");
                    return null;
                } else {
                    List<File> fileList = new ArrayList<>(Arrays.asList(files));
                    int index = fileList.indexOf(MediaLoader.this.lastPath);
                    if (index < 0) {
                        Log.e(TAG, "File no longer available");
                        return null;
                    } else {
                        if (direction == Direction.FORWARD) {
                            index += 1;
                        } else {
                            index -= 1;
                        }
                        index = index < 0 ? index + fileList.size() : index % fileList.size();
                        Log.d(TAG, String.format("File %s of %s", index + 1, fileList.size()));
                        file = fileList.get(index);
                    }
                }
            }
            if (file == null) {
                Log.e(TAG, "Failed to find a file");
                return null;
            }
            Uri uri = Uri.fromFile(file);
            Intent intent = new Intent(MediaLoader.this.context, MainActivity.class);
            intent.setData(uri);
            MediaLoader.this.context.startActivity(intent);
            return null;
        }
    }
}
