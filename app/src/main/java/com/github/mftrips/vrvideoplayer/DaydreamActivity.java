package com.github.mftrips.vrvideoplayer;

import android.content.Context;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;

import com.google.vr.sdk.controller.Controller;
import com.google.vr.sdk.controller.ControllerManager;

import org.metafetish.buttplug.core.ButtplugEvent;

public class DaydreamActivity extends CardboardActivity {
    private static final String TAG = DaydreamActivity.class.getSimpleName();

    // Interfaces with the Daydream controller.
    private ControllerManager controllerManager;
    private Controller controller;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Configure Controller.
        ControllerEventListener listener = new ControllerEventListener();
        controllerManager = new ControllerManager(this, listener);
        controller = controllerManager.getController();
        controller.setEventListener(listener);
        // controller.start() is called in onResume().
    }

    @Override
    protected void onResume() {
        super.onResume();
        controllerManager.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        controllerManager.stop();
    }

    @Override
    protected void recenter() {
        // do nothing
    }

    /** Forwards Controller events to SceneRenderer. */
    private class ControllerEventListener extends Controller.EventListener
            implements ControllerManager.EventListener {
        private boolean touchpadDown = false;
        private boolean appButtonDown = false;

        @Override
        public void onApiStatusChanged(int status) {
            Log.i(TAG, ".onApiStatusChanged " + status);
        }

        @Override
        public void onRecentered() {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                vibrator.vibrate(50);
            }
            rotationOffsets[0] = lastRotationOffsets[0];
            application.setVerticalOffset(rotationOffsets[0]);
        }

        @Override
        public void onUpdate() {
            controller.update();


            if (!touchpadDown && controller.clickButtonState) {
                if (controller.touch.x < 0.33) {
                    if (controller.touch.y < 0.33) {
                        mediaLoader.previous();
                    } else if (controller.touch.y > 0.66) {
                        onDestroy();
                    } else {
                        mediaLoader.seek(MediaLoader.Direction.BACKWARD, MediaLoader.Magnitude.SMALL);
                        syncListener.update();
                    }
                } else if(controller.touch.x > 0.66) {
                    if (controller.touch.y < 0.33) {
                        mediaLoader.next();
                    } else if (controller.touch.y > 0.66) {
                        mediaLoader.smallBack();
                        syncListener.update();
                    } else {
                        mediaLoader.seek(MediaLoader.Direction.FORWARD, MediaLoader.Magnitude.SMALL);
                        syncListener.update();
                    }
                } else {
                    if (controller.touch.y < 0.33) {
                        mediaLoader.seek(MediaLoader.Direction.FORWARD, MediaLoader.Magnitude.LARGE);
                        syncListener.update();
                    } else if (controller.touch.y > 0.66) {
                        mediaLoader.seek(MediaLoader.Direction.BACKWARD, MediaLoader.Magnitude.LARGE);
                        syncListener.update();
                    } else {
                        MediaLoader.State state = mediaLoader.playPause();
                        if (state == MediaLoader.State.PLAYING) {
                            syncListener.update();
                        } else {
                            syncListener.pause();
                        }
                    }
                }
            }

            if (!appButtonDown && controller.appButtonState) {
                scanHandler.invoke(new ButtplugEvent());
            }

            touchpadDown = controller.clickButtonState;
            appButtonDown = controller.appButtonState;
        }
    }
}
