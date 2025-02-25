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
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProxyVpnService extends VpnService {
    private static final String TAG = "ProxyVPN";

    public static final String ACTION_CONNECT = "com.example.oxylabs.START";
    public static final String ACTION_DISCONNECT = "com.example.oxylabs.STOP";

    private ParcelFileDescriptor vpnInterface;
    private Socket proxySocket;
    private volatile boolean isRunning;
    private ExecutorService executorService;

    // Proxy info (SOCKS5)
    private static final String PROXY_HOST = "23.94.138.75";
    private static final int PROXY_PORT = 6349;
    private static final String PROXY_USER = "ogrhvojb";
    private static final String PROXY_PASS = "3674h673992t";
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
            // 1) Build the TUN interface with a new private IP, e.g. 10.0.0.2/24
            vpnInterface = new Builder()
                    .addAddress("10.0.0.2", 24)    // Changed from 192.168.0.1
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    .setSession("OxyVPN (SOCKS5)")
                    .setMtu(1500)
                    .establish();

            isRunning = true;
            executorService = Executors.newFixedThreadPool(2);

            // 2) Connect to the SOCKS5 proxy instead of HTTP
            connectToSocks5Proxy();

            // 3) Start piping data from TUN -> Proxy and Proxy -> TUN
            startTunnel();

            // 4) Show an ongoing notification that VPN is connected
            showNotification("VPN Connected via SOCKS5");

        } catch (Exception e) {
            Log.e(TAG, "VPN setup failed", e);
            cleanup();
        }
    }

    /**
     * connectToSocks5Proxy():
     *   1) Open a TCP socket to the SOCKS5 proxy.
     *   2) Perform username/password authentication.
     *   3) Issue the CONNECT command to finalize the tunnel.
     */
    private void connectToSocks5Proxy() throws Exception {
        // 1) Create the socket and connect
        proxySocket = new Socket();
        // If the proxy blocks certain subnets, ensure the TUN IP is not restricted.
        proxySocket.connect(new InetSocketAddress(PROXY_HOST, PROXY_PORT), 10000);

        // 2) Exclude proxySocket from the VPN tunnel
        protect(proxySocket);

        // Streams for reading/writing
        OutputStream out = proxySocket.getOutputStream();
        InputStream in = proxySocket.getInputStream();

        // ---------------------------
        // SOCKS5 HANDSHAKE (Auth)
        // ---------------------------
        //   - We advertise username/password authentication (method = 0x02).
        //   - Greeting packet: [0x05, #methods=1, method=0x02]

        out.write(new byte[]{0x05, 0x01, 0x02});
        out.flush();

        // Read 2-byte response: [SOCKS version=0x05, chosen method=0x02]
        byte[] response = new byte[2];
        int read = in.read(response);
        if (read < 2 || response[0] != 0x05 || response[1] != 0x02) {
            throw new IOException("SOCKS5 proxy does not accept username/password auth. Got: "
                    + bytesToHex(response, read));
        }

        // ---------------------------
        // SOCKS5 Username/Password
        // ---------------------------
        // sub-negotiation: [version=0x01, userLength, user, passLength, pass]

        byte[] userBytes = PROXY_USER.getBytes();
        byte[] passBytes = PROXY_PASS.getBytes();

        byte[] authRequest = new byte[3 + userBytes.length + passBytes.length];
        authRequest[0] = 0x01; // sub-negotiation version
        authRequest[1] = (byte) userBytes.length;
        System.arraycopy(userBytes, 0, authRequest, 2, userBytes.length);
        // next index:
        int idx = 2 + userBytes.length;
        authRequest[idx] = (byte) passBytes.length;
        idx++;
        System.arraycopy(passBytes, 0, authRequest, idx, passBytes.length);

        out.write(authRequest);
        out.flush();

        // Read sub-negotiation response: [version=0x01, status=0x00=success]
        byte[] authResp = new byte[2];
        read = in.read(authResp);
        if (read < 2 || authResp[0] != 0x01 || authResp[1] != 0x00) {
            throw new IOException("SOCKS5 username/password auth failed. Response: "
                    + bytesToHex(authResp, read));
        }

        // ---------------------------
        // SOCKS5 CONNECT command
        // ---------------------------
        //   [version=0x05, cmd=0x01, rsv=0x00, addrType=0x01(IPv4), ip, port]
        //   We'll connect to the same PROXY_HOST:PROXY_PORT or a custom endpoint.

        byte[] connectRequest = new byte[10];
        connectRequest[0] = 0x05;       // version
        connectRequest[1] = 0x01;       // CONNECT
        connectRequest[2] = 0x00;       // reserved
        connectRequest[3] = 0x01;       // addrType=IPv4

        // Convert PROXY_HOST "23.94.138.75" => [23, 94, 138, 75]
        String[] octets = PROXY_HOST.split("\\.");
        connectRequest[4] = (byte) Integer.parseInt(octets[0]);
        connectRequest[5] = (byte) Integer.parseInt(octets[1]);
        connectRequest[6] = (byte) Integer.parseInt(octets[2]);
        connectRequest[7] = (byte) Integer.parseInt(octets[3]);

        // port => network byte order
        connectRequest[8] = (byte) (PROXY_PORT >> 8);
        connectRequest[9] = (byte) (PROXY_PORT & 0xFF);

        out.write(connectRequest);
        out.flush();

        // Read CONNECT response: [version=0x05, rep=0x00=success, rsv=0x00, addrType, ...]
        byte[] connectResp = new byte[10];
        read = in.read(connectResp);
        if (read < 4 || connectResp[0] != 0x05 || connectResp[1] != 0x00) {
            throw new IOException("SOCKS5 connect command failed. Response: "
                    + bytesToHex(connectResp, read));
        }

        Log.d(TAG, "SOCKS5 connection established!");
    }

    /**
     * startTunnel() spawns two threads:
     *  - TUN -> Proxy
     *  - Proxy -> TUN
     */
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
                    // If no data is available, avoid busy-wait.
                    if (proxyInput.available() > 0) {
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

            // Create a Notification Channel (required for Android 8+)
            manager.createNotificationChannel(new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "OxyVPN Service",
                    NotificationManager.IMPORTANCE_DEFAULT
            ));

            Intent intent = new Intent(this, ToyVpnClient.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            startForeground(1, new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_vpn)   // Replace with your own icon
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

    /**
     * Utility method for debugging partial bytes in a short read.
     */
    private static String bytesToHex(byte[] buf, int length) {
        if (length < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", buf[i]));
        }
        return sb.toString();
    }
}
