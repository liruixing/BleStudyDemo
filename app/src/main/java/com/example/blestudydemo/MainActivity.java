package com.example.blestudydemo;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class MainActivity extends Activity {
    private static final String TAG = "BleStudyDemo";
    private static final int REQUEST_BLUETOOTH = 7;
    private static final int REQUEST_PICK_FILE = 8;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<BluetoothDevice> pairedDevices = new ArrayList<>();

    private BluetoothAdapter bluetoothAdapter;
    private BleHelper bleHelper;
    private ClassicBluetoothHelper classicHelper;

    private LinearLayout logBox;
    private EditText blePayloadInput;
    private Spinner pairedSpinner;
    private TextView bleClientState;
    private Uri selectedFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        bleHelper = new BleHelper(this, bluetoothAdapter, mainHandler, new BleHelper.Listener() {
            @Override
            public void onLog(String message) {
                log(message);
            }

            @Override
            public void onClientStateChanged(String state) {
                mainHandler.post(() -> bleClientState.setText(state));
            }
        });
        classicHelper = new ClassicBluetoothHelper(this, bluetoothAdapter, this::log);
        buildUi();
        refreshPairedDevices();
        requestBluetoothPermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bleHelper.release();
        classicHelper.release();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scrollView.addView(root);

        TextView title = text("BLE Study Demo", 24, true);
        root.addView(title);
        root.addView(text("BLE 用 GATT 小包传输状态/控制数据；经典蓝牙用 RFCOMM socket 传输文件。两台设备安装同一个 app，一台开服务端，另一台开客户端。", 14, false));

        root.addView(section("BLE 低功耗数据传输"));
        blePayloadInput = new EditText(this);
        blePayloadInput.setSingleLine(false);
        blePayloadInput.setMinLines(2);
        blePayloadInput.setText("hello from BLE client");
        root.addView(blePayloadInput, matchWrap());

        LinearLayout bleButtons = row();
        bleButtons.addView(button("启动 BLE 服务端", v -> startBleServer()));
        bleButtons.addView(button("停止", v -> stopBleServer()));
        root.addView(bleButtons);

        LinearLayout bleClientButtons = row();
        bleClientButtons.addView(button("扫描并连接", v -> startBleScan()));
        bleClientButtons.addView(button("发送 BLE 数据", v -> sendBlePayload()));
        root.addView(bleClientButtons);
        bleClientState = text("BLE 客户端：未连接", 14, false);
        root.addView(bleClientState);

        root.addView(section("经典蓝牙文件传输"));
        LinearLayout classicButtons = row();
        classicButtons.addView(button("启动文件接收端", v -> startClassicServer()));
        classicButtons.addView(button("停止接收端", v -> stopClassicServer()));
        root.addView(classicButtons);

        pairedSpinner = new Spinner(this);
        root.addView(pairedSpinner, matchWrap());

        LinearLayout fileButtons = row();
        fileButtons.addView(button("刷新配对设备", v -> refreshPairedDevices()));
        fileButtons.addView(button("选择文件", v -> pickFile()));
        fileButtons.addView(button("发送文件", v -> sendSelectedFile()));
        root.addView(fileButtons);

        root.addView(section("日志"));
        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(logBox, matchWrap());

        setContentView(scrollView);
    }

    private TextView section(String value) {
        TextView view = text(value, 18, true);
        view.setPadding(0, dp(18), 0, dp(8));
        return view;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(0xff1d252c);
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setMinHeight(dp(44));
        button.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void log(String message) {
        Log.d(TAG, message);
        mainHandler.post(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            TextView line = text(time + "  " + message, 13, false);
            logBox.addView(line, 0);
        });
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermissions() {
        if (hasBluetoothPermissions()) {
            log("蓝牙权限已就绪");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            }, REQUEST_BLUETOOTH);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_BLUETOOTH);
        }
    }

    private boolean ensureBluetoothReady() {
        if (bluetoothAdapter == null) {
            log("设备不支持蓝牙");
            return false;
        }
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return false;
        }
        if (!bluetoothAdapter.isEnabled()) {
            log("请先在系统设置中开启蓝牙");
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return false;
        }
        return true;
    }

    private void startBleServer() {
        if (!ensureBluetoothReady()) {
            return;
        }
        observeVoid("BLE 服务端启动失败：", bleHelper.startServer());
    }

    private void stopBleServer() {
        observeVoid("BLE 服务端停止失败：", bleHelper.stopServerAsync());
    }

    private void startBleScan() {
        if (!ensureBluetoothReady()) {
            return;
        }
        observeDevice("BLE 连接失败：", bleHelper.scanAndConnect(15000));
    }

    private void sendBlePayload() {
        observeInteger("BLE 发送失败：", bleHelper.sendPayload(blePayloadInput.getText().toString()));
    }

    private void refreshPairedDevices() {
        if (!hasBluetoothPermissions()) {
            updatePairedDevices(new ArrayList<>());
            return;
        }
        Future<List<BluetoothDevice>> future = classicHelper.getPairedDevicesAsync();
        new Thread(() -> {
            try {
                updatePairedDevices(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log("刷新配对设备失败：" + e.getCause().getMessage());
            }
        }, "paired-device-refresh").start();
    }

    private void startClassicServer() {
        if (!ensureBluetoothReady()) {
            return;
        }
        observeVoid("经典蓝牙接收端启动失败：", classicHelper.startFileServer());
    }

    private void stopClassicServer() {
        observeVoid("经典蓝牙接收端停止失败：", classicHelper.stopFileServerAsync());
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PICK_FILE);
    }

    private void sendSelectedFile() {
        if (!ensureBluetoothReady()) {
            return;
        }
        if (selectedFile == null) {
            log("请先选择要发送的文件");
            return;
        }
        int position = pairedSpinner.getSelectedItemPosition();
        if (position < 0 || position >= pairedDevices.size()) {
            log("请选择已配对的经典蓝牙设备");
            return;
        }
        observeLong("经典蓝牙发送失败：", classicHelper.sendFile(pairedDevices.get(position), selectedFile));
    }

    private void updatePairedDevices(List<BluetoothDevice> devices) {
        mainHandler.post(() -> {
            pairedDevices.clear();
            pairedDevices.addAll(devices);
            List<String> labels = new ArrayList<>();
            for (BluetoothDevice device : pairedDevices) {
                labels.add(deviceName(device) + "  " + device.getAddress());
            }
            if (labels.isEmpty()) {
                labels.add("没有已配对设备");
            }
            pairedSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        });
    }

    private void observeVoid(String errorPrefix, Future<Void> future) {
        new Thread(() -> {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log(errorPrefix + e.getCause().getMessage());
            }
        }, "future-observer").start();
    }

    private void observeDevice(String errorPrefix, Future<BluetoothDevice> future) {
        new Thread(() -> {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log(errorPrefix + e.getCause().getMessage());
            }
        }, "future-device-observer").start();
    }

    private void observeInteger(String errorPrefix, Future<Integer> future) {
        new Thread(() -> {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log(errorPrefix + e.getCause().getMessage());
            }
        }, "future-int-observer").start();
    }

    private void observeLong(String errorPrefix, Future<Long> future) {
        new Thread(() -> {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log(errorPrefix + e.getCause().getMessage());
            }
        }, "future-long-observer").start();
    }

    private String deviceName(BluetoothDevice device) {
        if (device == null) {
            return "unknown";
        }
        String name = device.getName();
        return name == null || name.trim().isEmpty() ? device.getAddress() : name;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK && data != null) {
            selectedFile = data.getData();
            log("已选择文件：" + selectedFile);
        }
    }
}
