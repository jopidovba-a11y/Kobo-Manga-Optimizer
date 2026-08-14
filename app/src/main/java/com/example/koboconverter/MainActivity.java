package com.example.koboconverter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements OptimizationService.ProgressListener {

    private static final int PICK_FILE_REQUEST = 1;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 2;

    private TextView tvStatus;
    private Button btnSelectFile;
    private Spinner spinnerDevice;
    private Spinner spinnerFormat;

    private DeviceProfile selectedDevice = DeviceProfile.KOBO_CLARA_BW;
    private DeviceProfile.OutputFormat selectedFormat = DeviceProfile.OutputFormat.CBZ;

    private ArrayAdapter<DeviceProfile.OutputFormat> formatAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSelectFile = findViewById(R.id.btnSelectFile);
        tvStatus = findViewById(R.id.tvStatus);
        spinnerDevice = findViewById(R.id.spinnerDevice);
        spinnerFormat = findViewById(R.id.spinnerFormat);

        setupSpinners();
        requestNotificationPermissionIfNeeded();

        btnSelectFile.setOnClickListener(v -> openFileChooser());
    }

    @Override
    protected void onStart() {
        super.onStart();
        OptimizationService.setListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        OptimizationService.setListener(null);
    }

    private void setupSpinners() {
        ArrayAdapter<DeviceProfile> deviceAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, DeviceProfile.values());
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevice.setAdapter(deviceAdapter);

        formatAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, DeviceProfile.OutputFormat.values());
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFormat.setAdapter(formatAdapter);

        spinnerDevice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedDevice = DeviceProfile.values()[position];
                // Autoselecciona el formato recomendado para ese dispositivo
                int formatPos = selectedDevice.defaultFormat.ordinal();
                spinnerFormat.setSelection(formatPos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        spinnerFormat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedFormat = DeviceProfile.OutputFormat.values()[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
            }
        }
    }

    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK && data != null) {
            ArrayList<Uri> uris = new ArrayList<>();

            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }

            if (!uris.isEmpty()) {
                btnSelectFile.setEnabled(false);
                tvStatus.setText("Starting optimization of " + uris.size() + " files...");

                Intent serviceIntent = new Intent(this, OptimizationService.class);
                serviceIntent.putParcelableArrayListExtra(OptimizationService.EXTRA_URIS, uris);
                serviceIntent.putExtra(OptimizationService.EXTRA_DEVICE, selectedDevice.name());
                serviceIntent.putExtra(OptimizationService.EXTRA_FORMAT, selectedFormat.name());

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            }
        }
    }

    @Override
    public void onProgress(String message) {
        tvStatus.setText(message);
    }

    @Override
    public void onSuccess(String message) {
        tvStatus.setText(message);
        btnSelectFile.setEnabled(true);
        Toast.makeText(this, "Process completed", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onError(String message) {
        tvStatus.setText("Error: " + message);
        btnSelectFile.setEnabled(true);
    }
}