package com.github.mftrips.vrvideoplayer;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Handler;

import org.metafetish.buttplug.client.ButtplugClient;
import org.metafetish.buttplug.client.ButtplugEmbeddedClient;
import org.metafetish.buttplug.core.ButtplugEvent;
import org.metafetish.buttplug.core.ButtplugLogManager;
import org.metafetish.buttplug.core.IButtplugCallback;
import org.metafetish.buttplug.core.IButtplugLog;
import org.metafetish.buttplug.server.ButtplugServer;
import org.metafetish.buttplug.server.IButtplugServerFactory;
import org.metafetish.buttplug.server.managers.androidbluetoothmanager.AndroidBluetoothManager;

import java.util.concurrent.ExecutionException;

public class ButtplugApplication extends Application implements IButtplugServerFactory {
    private static final long SCAN_PERIOD = 15000;

    private Handler handler = new Handler();
    private Activity activity;

    private ButtplugLogManager bpLogManager = new ButtplugLogManager();
    private IButtplugLog bpLogger = this.bpLogManager.getLogger(this.getClass().getSimpleName());
    private String serverName;
    private ButtplugClient client;
    private HapticsManager hapticsManager;

    public HapticsManager getHapticsManager() {
        return this.hapticsManager;
    }

    private boolean initialized = false;
    private boolean isScanning = false;

    public void initialize(Activity activity) {
        this.activity = activity;
        ((MainActivity) activity).getScanHandler().addCallback(this.scanCallback);
        if (!this.initialized) {
            this.setServerDetails("vrVideoPlayer-server");
            this.client = new ButtplugEmbeddedClient("vrVideoPlayer-client", this);
            this.hapticsManager = new HapticsManager(this.client);
            this.client.initialized.addCallback(this.scanCallback);
            try {
                this.client.connect();
            } catch (Exception e) {
                this.bpLogger.logException(e);
            }
            this.initialized = true;
        }
    }

    private ButtplugServer initializeButtplugServer(String serverName) {
        final ButtplugServer bpServer = new ButtplugServer(serverName);
        bpServer.addDeviceSubtypeManager(new AndroidBluetoothManager(this.activity));
        return bpServer;
    }

    public void setServerDetails(String serverName) {
        this.serverName = serverName;

        String codeName;
        if (Build.VERSION.SDK_INT <= 20) {
            codeName = "Kit Kat";
        } else if (Build.VERSION.SDK_INT <= 22) {
            codeName = "Lollipop";
        } else if (Build.VERSION.SDK_INT == 23) {
            codeName = "Marshmallow";
        } else if (Build.VERSION.SDK_INT <= 25) {
            codeName = "Nougat";
        } else if (Build.VERSION.SDK_INT <= 27) {
            codeName = "Oreo";
        } else {
            codeName = "P";
        }
        this.bpLogger.info(String.format("Android %s.%s (%s, SDK %s)",
                Build.VERSION.RELEASE,
                Build.VERSION.INCREMENTAL,
                codeName,
                Build.VERSION.SDK_INT));
    }

    @Override
    public ButtplugServer getServer() {
        if (this.serverName == null) {
            throw new IllegalStateException("setServerDetails() must be called before getServer()");
        }
        return this.initializeButtplugServer(this.serverName);
    }

    private IButtplugCallback scanCallback = new IButtplugCallback() {
        @Override
        public void invoke(ButtplugEvent event) {
            if (!ButtplugApplication.this.isScanning) {
                try {
                    ButtplugApplication.this.bpLogger.warn("startScanning()");
                    ButtplugApplication.this.client.startScanning();
                    ButtplugApplication.this.isScanning = true;
                    ButtplugApplication.this.handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                ButtplugApplication.this.bpLogger.warn("stopScanning()");
                                ButtplugApplication.this.client.stopScanning();
                                ButtplugApplication.this.isScanning = false;
                            } catch (ExecutionException | InterruptedException e) {
                                ButtplugApplication.this.bpLogger.logException(e);
                            }
                        }
                    }, SCAN_PERIOD);
                } catch (ExecutionException | InterruptedException e) {
                    ButtplugApplication.this.bpLogger.logException(e);
                    ButtplugApplication.this.isScanning = false;
                }
            }
        }
    };
}
