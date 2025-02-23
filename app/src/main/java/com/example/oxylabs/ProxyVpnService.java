package com.example.oxylabs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Base64;

public class ProxyVpnService extends VpnService {
    private static final String TAG = "ProxyVPN";

    public static final String ACTION_CONNECT = "com.example.oxylabs.START";
    public static final String ACTION_DISCONNECT = "com.example.oxylabs.STOP";

    private ParcelFileDescriptor vpnInterface;
    private Socket proxySocket;
    private volatile boolean isRunning;
    private ExecutorService executorService;

    // Hna dir les infos dial Oxylabs dyalek
    private static final String PROXY_HOST = "192.168.11.108";
    private static final int PROXY_PORT = 8888;
    private static final String PROXY_USER = "elkasmi";
    private static final String PROXY_PASS = "test123456789";
    private static final int BUFFER_SIZE = 32767;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            cleanup();
            return START_NOT_STICKY;
        } else {
            if (!isRunning) {
                new Thread(() -> startVpn()).start();
            }
            return START_STICKY;
        }
    }

    private void startVpn() {
        try {
            vpnInterface = new Builder()
                    .addAddress("192.168.0.1", 24)
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .setSession("OxyVPN")
                    .setMtu(1500)
                    .establish();

            isRunning = true;
            executorService = Executors.newFixedThreadPool(2);
            connectToProxy();
            startTunnel();
            showNotification("VPN Connected");

        } catch (Exception e) {
            Log.e(TAG, "VPN setup failed", e);
            cleanup();
        }
    }

    private void connectToProxy() throws Exception {
        proxySocket = new Socket();
        proxySocket.connect(new InetSocketAddress(PROXY_HOST, PROXY_PORT), 10000);
        protect(proxySocket);

        String auth = "Basic " + Base64.getEncoder().encodeToString(
                (PROXY_USER + ":" + PROXY_PASS).getBytes());

        OutputStream out = proxySocket.getOutputStream();
        String proxyConnect = "CONNECT " + PROXY_HOST + ":" + PROXY_PORT + " HTTP/1.1\r\n" +
                "Host: " + PROXY_HOST + ":" + PROXY_PORT + "\r\n" +
                "Proxy-Authorization: " + auth + "\r\n" +
                "Connection: Keep-Alive\r\n\r\n";
        out.write(proxyConnect.getBytes());
        out.flush();

        // Kanchof la réponse dial proxy
        InputStream in = proxySocket.getInputStream();
        byte[] buffer = new byte[1024];
        int read = in.read(buffer);
        String response = new String(buffer, 0, read);
        Log.d(TAG, "Proxy response: " + response);

        if (!response.contains("200 OK")) {
            throw new IOException("Proxy connection failed: " + response);
        }
    }

    private void startTunnel() {
        // VPN --> Proxy
        executorService.submit(() -> {
            try {
                FileInputStream vpnInput = new FileInputStream(vpnInterface.getFileDescriptor());
                OutputStream proxyOutput = proxySocket.getOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];

                while (isRunning && !Thread.interrupted()) {
                    int read = vpnInput.read(buffer);
                    if (read > 0) {
                        proxyOutput.write(buffer, 0, read);
                        proxyOutput.flush();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "VPN to Proxy error", e);
                cleanup();
            }
        });

        // Proxy --> VPN
        executorService.submit(() -> {
            try {
                InputStream proxyInput = proxySocket.getInputStream();
                FileOutputStream vpnOutput = new FileOutputStream(vpnInterface.getFileDescriptor());
                byte[] buffer = new byte[BUFFER_SIZE];

                while (isRunning && !Thread.interrupted()) {
                    int bytesAvailable = proxyInput.available();
                    if (bytesAvailable > 0) {
                        int read = proxyInput.read(buffer);
                        if (read > 0) {
                            vpnOutput.write(buffer, 0, read);
                            vpnOutput.flush();
                        }
                    } else {
                        Thread.sleep(10);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Proxy to VPN error", e);
                cleanup();
            }
        });
    }

    private void showNotification(final String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            String NOTIFICATION_CHANNEL_ID = "OxyVPN";
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "OxyVPN Service",
                    NotificationManager.IMPORTANCE_DEFAULT));

            Intent intent = new Intent(this, ToyVpnClient.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            startForeground(1, new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_vpn)
                    .setContentText(message)
                    .setContentIntent(pendingIntent)
                    .build());
        });
    }

    private void cleanup() {
        isRunning = false;
        try {
            if (executorService != null) {
                executorService.shutdownNow();
            }
            if (proxySocket != null) {
                proxySocket.close();
            }
            if (vpnInterface != null) {
                vpnInterface.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Cleanup error", e);
        }
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }
}