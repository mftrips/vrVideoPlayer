package com.github.mftrips.vrvideoplayer;

import android.media.MediaPlayer;
import android.util.SparseArray;

import org.metafetish.buttplug.client.ButtplugClient;
import org.metafetish.buttplug.core.ButtplugDeviceMessage;
import org.metafetish.buttplug.core.ButtplugLogManager;
import org.metafetish.buttplug.core.IButtplugLog;
import org.metafetish.buttplug.core.Messages.DeviceMessageInfo;
import org.metafetish.haptic_file_reader.Commands.HapticCommand;
import org.metafetish.haptic_file_reader.HapticFileHandler;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class HapticsManager {
    private ButtplugLogManager bpLogManager = new ButtplugLogManager();
    private IButtplugLog bpLogger = this.bpLogManager.getLogger(this.getClass().getSimpleName());

    private Future<?> looper;

    private ButtplugClient buttplug;
    private Map<Long, DeviceMessageInfo> devices;

    private MediaPlayer mediaPlayer;

    private SparseArray<List<ButtplugDeviceMessage>> commands;

    //TODO: Make offset configurable
    private int offset = 50;
    private int lastIndexRetrieved;
    private int lastTimeChecked;
    private boolean paused;

    HapticsManager(ButtplugClient buttplug) {
        this.buttplug = buttplug;
        this.devices = this.buttplug.getDevices();
    }

    public void onMediaLoaded(File videoFile, MediaPlayer mediaPlayer) {
        this.mediaPlayer = mediaPlayer;
        this.lastIndexRetrieved = -1;
        this.lastTimeChecked = 0;
        this.paused = false;

        File parent = videoFile.getParentFile();
        if (parent.isDirectory() && parent.canRead()) {
            File[] hapticsFiles = parent.listFiles(new HapticFileFilter(videoFile));
            if (hapticsFiles.length != 0) {
                List<HapticCommand> hapticCommands = null;
                for (File hapticsFile : hapticsFiles) {
                    HapticFileHandler hapticFileHandler = HapticFileHandler.handleFile(hapticsFile);
                    if (hapticFileHandler != null) {
                        if (hapticCommands == null) {
                            hapticCommands = hapticFileHandler.getCommands();
                        } else {
                            this.bpLogger.warn("Multiple parsers found");
                        }
                    }
                }
                if (hapticCommands != null) {
                    this.bpLogger.debug("Haptics file parsed successfully");
                    this.bpLogger.debug(String.format("Paused?: %s", this.paused));

                    this.commands = HapticCommandToButtplugMessage.hapticCommandToButtplugMessage(hapticCommands);
                    if (!this.paused) {
                        this.onPlay();
                    }
                } else {
                    this.bpLogger.warn("Unable to parse haptics file");
                }

            } else {
                this.bpLogger.debug("No haptics files found");
            }
        } else {
            this.bpLogger.debug("Can't read parent directory");
        }
    }

    public void onPlay() {
        this.bpLogger.debug("onPlay called");
        this.paused = false;
        if (this.looper == null || this.looper.isDone() || this.looper.isCancelled()) {
            this.runHapticsLoop();
        }
    }

    public void onPause() {
        this.bpLogger.debug("onPaused called");
        if (this.devices.size() > 0) {
            try {
                this.buttplug.stopAllDevices();
            } catch (ExecutionException | InterruptedException e) {
                this.bpLogger.logException(e);
            }
        }
        if (this.looper != null) {
            this.looper.cancel(true);
        }
        this.paused = true;
    }

    public void onSeek() {
        this.bpLogger.debug("onSeek called");
        this.paused = false;
        if (this.looper == null || this.looper.isDone() || this.looper.isCancelled()) {
            this.runHapticsLoop();
        }
    }

    private void runHapticsLoop() {
        this.looper = Executors.newSingleThreadExecutor().submit(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    // If we paused before this fired, just return
                    if (HapticsManager.this.paused || HapticsManager.this.commands == null || HapticsManager.this.commands.size() == 0 || HapticsManager.this.mediaPlayer == null) {
                        break;
                    }
                    final int currentPlayTime = HapticsManager.this.mediaPlayer.getCurrentPosition() + HapticsManager.this.offset;
                    // Backwards seek. Reset index retrieved.
                    // MediaPlayer.getCurrentPosition() is bugged, don't reset unless diff is more than 500ms
                    //TODO: Switch to exoplayer
                    if (currentPlayTime + 500 < HapticsManager.this.lastTimeChecked) {
                        HapticsManager.this.bpLogger.debug(String.format("Restarting (%s <= %s)",
                                currentPlayTime + 500, HapticsManager.this.lastTimeChecked));
                        HapticsManager.this.lastIndexRetrieved = -1;
                    }
                    HapticsManager.this.lastTimeChecked = currentPlayTime;
                    if (HapticsManager.this.lastIndexRetrieved + 1 > HapticsManager.this.commands.size()) {
                        // We're at the end of our haptics data
                        break;
                    }
                    if (currentPlayTime <= HapticsManager.this.commands.keyAt(HapticsManager.this.lastIndexRetrieved + 1)) {
                        continue;
                    }
                    // There are faster ways to do this.
                    while (currentPlayTime > HapticsManager.this.commands.keyAt(HapticsManager.this.lastIndexRetrieved + 1)) {
                        HapticsManager.this.lastIndexRetrieved += 1;
                    }
                    final List<ButtplugDeviceMessage> msgs = HapticsManager.this.commands.get(HapticsManager.this.commands.keyAt(HapticsManager.this.lastIndexRetrieved));
                    if (msgs != null) {
                        for (final ButtplugDeviceMessage msg : msgs) {
                            for (final Map.Entry<Long, DeviceMessageInfo> device : HapticsManager.this.devices.entrySet()) {
                                if (!device.getValue().deviceMessages.containsKey(msg.getClass().getSimpleName())) {
                                    continue;
                                }
                                HapticsManager.this.bpLogger.debug(String.format("Sending %s to %s", HapticsManager.this.lastIndexRetrieved, device.getKey()));
                                HapticsManager.this.buttplug.sendDeviceMessage(device.getKey(), msg);
                            }
                        }
                    }
                }
            }
        });
    }
}
