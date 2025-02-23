package com.example.oxylabs;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

public class ToyVpnClient extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.form);

        Button connectButton = findViewById(R.id.connect);
        Button disconnectButton = findViewById(R.id.disconnect);

        connectButton.setOnClickListener(v -> {
            Intent intent = VpnService.prepare(ToyVpnClient.this);
            if (intent != null) {
                startActivityForResult(intent, 0);
            } else {
                onActivityResult(0, RESULT_OK, null);
            }
        });

        disconnectButton.setOnClickListener(v -> {
            startService(getServiceIntent().setAction(ProxyVpnService.ACTION_DISCONNECT));
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (result == RESULT_OK) {
            startService(getServiceIntent().setAction(ProxyVpnService.ACTION_CONNECT));
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
        }
    }

    private Intent getServiceIntent() {
        return new Intent(this, ProxyVpnService.class);
    }
}