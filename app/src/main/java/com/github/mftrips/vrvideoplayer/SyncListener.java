package com.github.mftrips.vrvideoplayer;

import android.media.MediaPlayer;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

class SyncListener {
    private static final String TAG = "SyncListener";

    private static final int SYNC_LISTENER_PORT = 2000;

    private SyncListenerThread socketServerThread;
    private MediaPlayer mediaPlayer;
    private ServerSocket serverSocket;
    private String filename;

    private boolean isDestroyed = false;

    SyncListener() {
        socketServerThread = new SyncListenerThread();
        socketServerThread.start();
    }

    void setMediaPlayer(MediaPlayer mediaPlayer) {
        this.mediaPlayer = mediaPlayer;
    }

    void setFilename(String filename) {
        this.filename = filename;
        Thread thread = new Thread() {
            public void run() {
                socketServerThread.open();
            }
        };
        thread.start();
    }

    void pause() {
        Thread thread = new Thread() {
            public void run() {
                socketServerThread.pause();
            }
        };
        thread.start();
    }

    void update() {
        Thread thread = new Thread() {
            public void run() {
                socketServerThread.update();
            }
        };
        thread.start();
    }

    void destroy() {
        isDestroyed = true;
        Thread thread = new Thread() {
            public void run() {
                socketServerThread.pause();
                socketServerThread.close();
            }
        };
        thread.start();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public class SyncListenerThread extends Thread {
        Socket socket;
        private PrintStream printStream;
        private boolean isPaused = true;

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(SYNC_LISTENER_PORT);

                Log.d(TAG, "Listening ...");
                while (!isDestroyed) {
                    socket = serverSocket.accept();
                    Log.d(TAG, "Connected.");
                    OutputStream outputStream = socket.getOutputStream();
                    printStream = new PrintStream(outputStream);
                    if (filename != null) {
                        open();
                    } else {
                        pause();
                    }
                    while (!isDestroyed) {
                        if (!isPaused) {
                            update();
                        }
                        TimeUnit.SECONDS.sleep(1);
                    }
                    pause();
                    socket.close();
                }
            } catch (IOException | InterruptedException e) {
                if (!isDestroyed) {
                    e.printStackTrace();
                }
            }
        }

        void open() {
            if (socket != null && filename != null) {
                Log.d(TAG, "Sending filename: " + filename);
                printStream.println("C " + filename);
                isPaused = false;
            }
        }

        void update() {
            if (socket != null && mediaPlayer != null) {
                double position = (double) mediaPlayer.getCurrentPosition() / 1000;
                Log.d(TAG, "Sending position: " + position);
                printStream.println("P " + position);
                isPaused = false;
            }
        }

        void pause() {
            if (socket != null) {
                Log.d(TAG, "Sending pause");
                printStream.println("S");
            }
            isPaused = true;
        }

        void close() {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    if (!isDestroyed) {
                        e.printStackTrace();
                    }
                }
            }
            isPaused = true;
        }
    }
}
