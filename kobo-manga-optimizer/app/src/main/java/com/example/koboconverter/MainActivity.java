package com.example.koboconverter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
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

    private static final String PREFS_NAME = "koboconverter_prefs";
    private static final String PREF_DEVICE = "device";
    private static final String PREF_FORMAT = "format";
    private static final String PREF_QUALITY = "quality";
    private static final String PREF_ROTATION = "rotation";
    private static final String PREF_BORDER_COLOR = "border_color";
    private static final String PREF_COLOR_MODE = "color_mode";
    private static final String PREF_CUSTOM_WIDTH = "custom_width";
    private static final String PREF_CUSTOM_HEIGHT = "custom_height";

    private TextView tvStatus;
    private Button btnSelectFile;
    private Spinner spinnerDevice;
    private Spinner spinnerFormat;
    private RadioGroup radioGroupQuality;
    private RadioGroup radioGroupRotation;
    private RadioGroup radioGroupBorderColor;
    private CheckBox checkboxColorManga;
    private TextView labelCustomResolution;
    private View rowCustomResolution;
    private EditText editCustomWidth;
    private EditText editCustomHeight;

    private DeviceProfile selectedDevice = DeviceProfile.KOBO_CLARA_BW;
    private DeviceProfile.OutputFormat selectedFormat = DeviceProfile.OutputFormat.CBZ;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSelectFile = findViewById(R.id.btnSelectFile);
        tvStatus = findViewById(R.id.tvStatus);
        spinnerDevice = findViewById(R.id.spinnerDevice);
        spinnerFormat = findViewById(R.id.spinnerFormat);
        radioGroupQuality = findViewById(R.id.radioGroupQuality);
        radioGroupRotation = findViewById(R.id.radioGroupRotation);
        radioGroupBorderColor = findViewById(R.id.radioGroupBorderColor);
        checkboxColorManga = findViewById(R.id.checkboxColorManga);
        labelCustomResolution = findViewById(R.id.labelCustomResolution);
        rowCustomResolution = findViewById(R.id.rowCustomResolution);
        editCustomWidth = findViewById(R.id.editCustomWidth);
        editCustomHeight = findViewById(R.id.editCustomHeight);

        // Quality 100 is hidden: the Rust encoder caps JPEG at 95 (source
        // quality) anyway, and the image crate's quality 100 produces
        // pathologically large files. Users get identical output from 95.
        findViewById(R.id.radioQuality100).setVisibility(View.GONE);

        setupSpinners();
        loadSavedSettings();
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

        ArrayAdapter<DeviceProfile.OutputFormat> formatAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, DeviceProfile.OutputFormat.values());
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFormat.setAdapter(formatAdapter);

        spinnerDevice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedDevice = DeviceProfile.values()[position];
                spinnerFormat.setSelection(selectedDevice.defaultFormat.ordinal());
                updateCustomFieldsVisibility();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        spinnerFormat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedFormat = DeviceProfile.OutputFormat.values()[position];
                updateQualityFieldsEnabled();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void updateCustomFieldsVisibility() {
        int visibility = selectedDevice == DeviceProfile.CUSTOM ? View.VISIBLE : View.GONE;
        labelCustomResolution.setVisibility(visibility);
        rowCustomResolution.setVisibility(visibility);
    }

    private void updateQualityFieldsEnabled() {
        boolean isPdf = selectedFormat == DeviceProfile.OutputFormat.PDF;
        findViewById(R.id.radioQuality85).setEnabled(!isPdf);
        findViewById(R.id.radioQuality95).setEnabled(!isPdf);
        // radioQuality100 is hidden; no need to toggle it.

        if (isPdf) {
            int checkedId = radioGroupQuality.getCheckedRadioButtonId();
            if (checkedId == R.id.radioQuality85 || checkedId == R.id.radioQuality95) {
                radioGroupQuality.check(R.id.radioQualityLossless);
            }
        }
    }

    private void loadSavedSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        try {
            selectedDevice = DeviceProfile.valueOf(prefs.getString(PREF_DEVICE, DeviceProfile.KOBO_CLARA_BW.name()));
        } catch (IllegalArgumentException e) {
            selectedDevice = DeviceProfile.KOBO_CLARA_BW;
        }
        spinnerDevice.setSelection(selectedDevice.ordinal());

        try {
            selectedFormat = DeviceProfile.OutputFormat.valueOf(prefs.getString(PREF_FORMAT, selectedDevice.defaultFormat.name()));
        } catch (IllegalArgumentException e) {
            selectedFormat = selectedDevice.defaultFormat;
        }
        spinnerFormat.setSelection(selectedFormat.ordinal());

        int quality = prefs.getInt(PREF_QUALITY, 85);
        if (quality == 95) radioGroupQuality.check(R.id.radioQuality95);
        else if (quality == 100) radioGroupQuality.check(R.id.radioQuality95); // migrate 100 -> 95
        else if (quality == CbzOptimizer.QUALITY_LOSSLESS) radioGroupQuality.check(R.id.radioQualityLossless);
        else radioGroupQuality.check(R.id.radioQuality85);

        String rotation = prefs.getString(PREF_ROTATION, "NONE");
        if (rotation.equals("AUTO_HORIZONTAL")) radioGroupRotation.check(R.id.radioRotateAuto);
        else if (rotation.equals("ALL")) radioGroupRotation.check(R.id.radioRotateAll);
        else radioGroupRotation.check(R.id.radioRotateNone);

        String borderColor = prefs.getString(PREF_BORDER_COLOR, "WHITE");
        if (borderColor.equals("BLACK")) radioGroupBorderColor.check(R.id.radioBorderBlack);
        else if (borderColor.equals("AUTO")) radioGroupBorderColor.check(R.id.radioBorderAuto);
        else radioGroupBorderColor.check(R.id.radioBorderWhite);

        checkboxColorManga.setChecked(prefs.getBoolean(PREF_COLOR_MODE, false));

        editCustomWidth.setText(String.valueOf(prefs.getInt(PREF_CUSTOM_WIDTH, 1072)));
        editCustomHeight.setText(String.valueOf(prefs.getInt(PREF_CUSTOM_HEIGHT, 1448)));

        updateCustomFieldsVisibility();
        updateQualityFieldsEnabled();
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(PREF_DEVICE, selectedDevice.name());
        editor.putString(PREF_FORMAT, selectedFormat.name());
        editor.putInt(PREF_QUALITY, getSelectedQuality());
        editor.putString(PREF_ROTATION, getSelectedRotation());
        editor.putString(PREF_BORDER_COLOR, getSelectedBorderColor());
        editor.putBoolean(PREF_COLOR_MODE, checkboxColorManga.isChecked());
        editor.putInt(PREF_CUSTOM_WIDTH, parseIntOrDefault(editCustomWidth.getText().toString(), 1072));
        editor.putInt(PREF_CUSTOM_HEIGHT, parseIntOrDefault(editCustomHeight.getText().toString(), 1448));
        editor.apply();
    }

    private int getSelectedQuality() {
        int checkedId = radioGroupQuality.getCheckedRadioButtonId();
        if (checkedId == R.id.radioQuality95) return 95;
        if (checkedId == R.id.radioQuality100) return 95; // hidden, defensive
        if (checkedId == R.id.radioQualityLossless) return CbzOptimizer.QUALITY_LOSSLESS;
        return 85;
    }

    private String getSelectedRotation() {
        int checkedId = radioGroupRotation.getCheckedRadioButtonId();
        if (checkedId == R.id.radioRotateAuto) return "AUTO_HORIZONTAL";
        if (checkedId == R.id.radioRotateAll) return "ALL";
        return "NONE";
    }

    private String getSelectedBorderColor() {
        int checkedId = radioGroupBorderColor.getCheckedRadioButtonId();
        if (checkedId == R.id.radioBorderBlack) return "BLACK";
        if (checkedId == R.id.radioBorderAuto) return "AUTO";
        return "WHITE";
    }

    private int parseIntOrDefault(String text, int defaultValue) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Integer tryParseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return null;
        }
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

            if (uris.isEmpty()) return;

            int targetWidth;
            int targetHeight;
            if (selectedDevice == DeviceProfile.CUSTOM) {
                Integer w = tryParseInt(editCustomWidth.getText().toString());
                Integer h = tryParseInt(editCustomHeight.getText().toString());
                if (w == null || h == null || w <= 0 || h <= 0) {
                    Toast.makeText(this, "Enter a valid custom width and height", Toast.LENGTH_LONG).show();
                    return;
                }
                targetWidth = w;
                targetHeight = h;
            } else {
                targetWidth = selectedDevice.width;
                targetHeight = selectedDevice.height;
            }

            saveSettings();

            btnSelectFile.setEnabled(false);
            tvStatus.setText("Starting optimization of " + uris.size() + " files...");

            int quality = getSelectedQuality();
            String rotationMode = getSelectedRotation();
            String borderColor = getSelectedBorderColor();
            boolean colorMangaMode = checkboxColorManga.isChecked();

            Intent serviceIntent = new Intent(this, OptimizationService.class);
            serviceIntent.putParcelableArrayListExtra(OptimizationService.EXTRA_URIS, uris);
            serviceIntent.putExtra(OptimizationService.EXTRA_WIDTH, targetWidth);
            serviceIntent.putExtra(OptimizationService.EXTRA_HEIGHT, targetHeight);
            serviceIntent.putExtra(OptimizationService.EXTRA_FORMAT, selectedFormat.name());
            serviceIntent.putExtra(OptimizationService.EXTRA_QUALITY, quality);
            serviceIntent.putExtra(OptimizationService.EXTRA_ROTATION, rotationMode);
            serviceIntent.putExtra(OptimizationService.EXTRA_BORDER_COLOR, borderColor);
            serviceIntent.putExtra(OptimizationService.EXTRA_COLOR_MODE, colorMangaMode);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }
    }

    @Override
    public void onProgress(String message) {
        btnSelectFile.setEnabled(false);
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