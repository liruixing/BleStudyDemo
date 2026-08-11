package com.example.blestudydemo;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * BLE 学习用工具类：同时封装 GATT Server 广播端和 GATT Client 扫描/写入端。
 *
 * <p>异步方法统一返回 {@link Future}。BLE 系统 API 本身主要靠回调通知结果，
 * 所以这里用 {@link SimpleFuture} 把“广播启动成功、扫描连接成功、发送完成”等
 * 事件桥接成 Future，方便 Activity 层等待或观察失败。</p>
 */
public class BleHelper {
    public static final UUID SERVICE_UUID = UUID.fromString("0000a100-0000-1000-8000-00805f9b34fb");
    public static final UUID DATA_UUID = UUID.fromString("0000a101-0000-1000-8000-00805f9b34fb");
    public static final UUID CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int DEFAULT_CHUNK_SIZE = 20;

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler mainHandler;
    private final Listener listener;
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();

    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothDevice serverClientDevice;
    private BluetoothLeScanner scanner;
    private BluetoothGatt clientGatt;
    private BluetoothGattCharacteristic clientWriteCharacteristic;
    // clientReady 表示 Characteristic 已发现，可以写数据；notifyReady 只表示 ack 通知可用。
    private boolean clientReady;
    private boolean notifyReady;
    private SimpleFuture<Void> startServerFuture;
    private SimpleFuture<BluetoothDevice> scanFuture;
    private SimpleFuture<Integer> sendFuture;
    private int serverReceivedBytes;
    private int pendingSendBytes;

    public BleHelper(Context context, BluetoothAdapter bluetoothAdapter, Handler mainHandler, Listener listener) {
        this.context = context.getApplicationContext();
        this.bluetoothAdapter = bluetoothAdapter;
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    public Future<Void> startServer() {
        SimpleFuture<Void> future = new SimpleFuture<>();
        if (bluetoothAdapter == null) {
            future.setException(new IllegalStateException("BluetoothAdapter is null"));
            return future;
        }
        stopServer();
        startServerFuture = future;

        // 服务端暴露一个 Primary Service + 一个可读/可写/可通知的 Characteristic。
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        gattServer = manager == null ? null : manager.openGattServer(context, gattServerCallback);
        if (gattServer == null) {
            future.setException(new IllegalStateException("GATT Server 创建失败"));
            return future;
        }

        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic data = new BluetoothGattCharacteristic(
                DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
                        | BluetoothGattCharacteristic.PERMISSION_WRITE
        );
        data.addDescriptor(new BluetoothGattDescriptor(
                CLIENT_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
        ));
        data.setValue("ready".getBytes(StandardCharsets.UTF_8));
        service.addCharacteristic(data);
        gattServer.addService(service);

        // 广播包里带 Service UUID，客户端扫描时用这个 UUID 识别目标设备。
        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            stopServer();
            future.setException(new IllegalStateException("设备不支持 BLE 广播"));
            return future;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();
        AdvertiseData dataPacket = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        advertiser.startAdvertising(settings, dataPacket, advertiseCallback);
        serverReceivedBytes = 0;
        emitLog("BLE 服务端已启动，Service=" + SERVICE_UUID);
        return future;
    }

    public Future<Void> stopServerAsync() {
        SimpleFuture<Void> future = new SimpleFuture<>();
        stopServer();
        future.set(null);
        return future;
    }

    public void stopServer() {
        if (advertiser != null) {
            advertiser.stopAdvertising(advertiseCallback);
            advertiser = null;
        }
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
        }
        serverClientDevice = null;
        emitLog("BLE 服务端已停止");
    }

    public Future<BluetoothDevice> scanAndConnect(long timeoutMillis) {
        SimpleFuture<BluetoothDevice> future = new SimpleFuture<>();
        if (bluetoothAdapter == null) {
            future.setException(new IllegalStateException("BluetoothAdapter is null"));
            return future;
        }
        stopClient();
        scanFuture = future;
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            future.setException(new IllegalStateException("BLE Scanner 不可用"));
            return future;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        // 不使用硬件 ScanFilter。部分机型对 128-bit Service UUID 过滤不稳定，
        // 所以这里全量扫描，再在 onScanResult 中做软件匹配。
        scanner.startScan(null, settings, scanCallback);
        emitState("BLE 客户端：扫描中");
        emitLog("BLE 客户端开始扫描，目标 Service=" + SERVICE_UUID);
        mainHandler.postDelayed(() -> {
            if (scanner != null && clientGatt == null) {
                scanner.stopScan(scanCallback);
                scanner = null;
                emitState("BLE 客户端：扫描超时");
                emitLog("BLE 扫描 " + (timeoutMillis / 1000) + " 秒超时");
                if (scanFuture != null) {
                    scanFuture.setException(new IllegalStateException("BLE scan timeout"));
                    scanFuture = null;
                }
            }
        }, timeoutMillis);
        return future;
    }

    public Future<Integer> sendPayload(String payload) {
        SimpleFuture<Integer> future = new SimpleFuture<>();
        if (clientGatt == null || clientWriteCharacteristic == null || !clientReady) {
            String state = "ready=" + clientReady
                    + ", notifyReady=" + notifyReady
                    + ", gatt=" + (clientGatt != null)
                    + ", characteristic=" + (clientWriteCharacteristic != null);
            future.setException(new IllegalStateException("请先扫描并连接 BLE 服务端：" + state));
            emitLog("请先扫描并连接 BLE 服务端：" + state);
            return future;
        }
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        writeQueue.clear();
        // 默认 ATT payload 演示按 20 bytes 分包。没有做 MTU 协商，便于观察基础模型。
        for (int i = 0; i < data.length; i += DEFAULT_CHUNK_SIZE) {
            int end = Math.min(data.length, i + DEFAULT_CHUNK_SIZE);
            byte[] chunk = new byte[end - i];
            System.arraycopy(data, i, chunk, 0, chunk.length);
            writeQueue.add(chunk);
        }
        sendFuture = future;
        pendingSendBytes = data.length;
        emitLog("BLE 客户端准备发送 " + data.length + " bytes，按 20 bytes 分包");
        writeNextChunk();
        return future;
    }

    public Future<Void> stopClientAsync() {
        SimpleFuture<Void> future = new SimpleFuture<>();
        stopClient();
        future.set(null);
        return future;
    }

    public void stopClient() {
        if (scanner != null) {
            scanner.stopScan(scanCallback);
            scanner = null;
        }
        if (clientGatt != null) {
            clientGatt.disconnect();
            clientGatt.close();
            clientGatt = null;
        }
        clientWriteCharacteristic = null;
        clientReady = false;
        notifyReady = false;
        writeQueue.clear();
    }

    public void release() {
        stopServer();
        stopClient();
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            emitLog("BLE 广播开始");
            if (startServerFuture != null) {
                startServerFuture.set(null);
                startServerFuture = null;
            }
        }

        @Override
        public void onStartFailure(int errorCode) {
            emitLog("BLE 广播失败 code=" + errorCode);
            if (startServerFuture != null) {
                startServerFuture.setException(new IllegalStateException("BLE advertise failed: " + errorCode));
                startServerFuture = null;
            }
        }
    };

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            serverClientDevice = newState == BluetoothProfile.STATE_CONNECTED ? device : null;
            emitLog("BLE 服务端连接状态：" + deviceName(device) + " -> " + newState);
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            byte[] value = ("server-bytes=" + serverReceivedBytes).getBytes(StandardCharsets.UTF_8);
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            emitLog("BLE 服务端收到读请求");
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            serverReceivedBytes += value == null ? 0 : value.length;
            String text = value == null ? "" : new String(value, StandardCharsets.UTF_8);
            emitLog("BLE 服务端收到 " + (value == null ? 0 : value.length) + " bytes: " + text);
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
            // 客户端如果已经写 CCCD 开启 Notify，这里会把累计接收字节数回推给客户端。
            if (serverClientDevice != null) {
                characteristic.setValue(("ack total=" + serverReceivedBytes).getBytes(StandardCharsets.UTF_8));
                gattServer.notifyCharacteristicChanged(serverClientDevice, characteristic, false);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
            byte[] value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            emitLog("BLE 服务端收到描述符读请求：" + descriptor.getUuid());
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            // 客户端开启 Notify 时会写 CCCD。服务端必须响应，否则后续 GATT 操作可能失败。
            if (value != null) {
                descriptor.setValue(value);
            }
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
            emitLog("BLE 服务端收到描述符写请求：" + descriptor.getUuid() + " value=" + bytesToHex(value));
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!hasTargetService(result)) {
                return;
            }
            BluetoothDevice device = result.getDevice();
            emitLog("发现 BLE 服务端：" + deviceName(device));
            if (scanner != null) {
                scanner.stopScan(this);
                scanner = null;
            }
            emitState("BLE 客户端：连接中 " + deviceName(device));
            clientGatt = device.connectGatt(context, false, gattCallback);
        }

        @Override
        public void onScanFailed(int errorCode) {
            emitLog("BLE 扫描失败 code=" + errorCode);
            if (scanFuture != null) {
                scanFuture.setException(new IllegalStateException("BLE scan failed: " + errorCode));
                scanFuture = null;
            }
        }
    };

    private boolean hasTargetService(ScanResult result) {
        ScanRecord scanRecord = result.getScanRecord();
        if (scanRecord == null) {
            return false;
        }
        List<ParcelUuid> serviceUuids = scanRecord.getServiceUuids();
        if (serviceUuids == null) {
            return false;
        }
        for (ParcelUuid serviceUuid : serviceUuids) {
            if (SERVICE_UUID.equals(serviceUuid.getUuid())) {
                return true;
            }
        }
        return false;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            emitLog("BLE 客户端连接状态 status=" + status + " state=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emitState("BLE 客户端：已连接，发现服务中");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                emitState("BLE 客户端：已断开");
                clientWriteCharacteristic = null;
                clientReady = false;
                notifyReady = false;
                if (scanFuture != null) {
                    scanFuture.setException(new IllegalStateException("BLE disconnected before ready"));
                    scanFuture = null;
                }
                if (sendFuture != null) {
                    sendFuture.setException(new IllegalStateException("BLE disconnected while sending"));
                    sendFuture = null;
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            clientWriteCharacteristic = service == null ? null : service.getCharacteristic(DATA_UUID);
            if (clientWriteCharacteristic == null) {
                emitLog("未找到学习用 BLE characteristic");
                if (scanFuture != null) {
                    scanFuture.setException(new IllegalStateException("BLE characteristic not found"));
                    scanFuture = null;
                }
                return;
            }
            // 写数据只依赖 Characteristic 发现成功；Notify 只是用于接收 ack。
            // 两者分开可避免 CCCD 写入在部分设备上失败时影响数据发送。
            markClientReady(gatt);
            gatt.setCharacteristicNotification(clientWriteCharacteristic, true);
            BluetoothGattDescriptor descriptor = clientWriteCharacteristic.getDescriptor(CLIENT_CONFIG_UUID);
            if (descriptor != null) {
                emitState("BLE 客户端：已连接，可发送小包数据，开启通知中");
                if (!writeDescriptorCompat(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    emitLog("BLE 开启通知失败：writeDescriptor 返回 false");
                }
                return;
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            emitLog("BLE 描述符写入完成 status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS && CLIENT_CONFIG_UUID.equals(descriptor.getUuid())) {
                notifyReady = true;
                emitLog("BLE 通知已就绪");
                return;
            }
            emitLog("BLE 通知开启失败，发送写入仍可继续：status=" + status);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            emitLog("BLE 客户端写入完成 status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (sendFuture != null) {
                    sendFuture.setException(new IllegalStateException("BLE write failed: " + status));
                    sendFuture = null;
                }
                return;
            }
            writeNextChunk();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            emitLog("BLE 客户端收到通知：" + new String(characteristic.getValue(), StandardCharsets.UTF_8));
        }
    };

    private void writeNextChunk() {
        if (clientGatt == null || clientWriteCharacteristic == null) {
            return;
        }
        if (writeQueue.isEmpty()) {
            if (sendFuture != null) {
                sendFuture.set(pendingSendBytes);
                sendFuture = null;
            }
            return;
        }
        byte[] chunk = writeQueue.poll();
        // Android BLE 一次只能有一个 GATT 写操作在队列中；下一包在 onCharacteristicWrite 后发送。
        if (!writeCharacteristicCompat(clientGatt, clientWriteCharacteristic, chunk)) {
            emitLog("BLE 客户端写入请求失败：writeCharacteristic 返回 false, ready=" + clientReady
                    + ", notifyReady=" + notifyReady
                    + ", gatt=" + (clientGatt != null)
                    + ", characteristic=" + (clientWriteCharacteristic != null));
            if (sendFuture != null) {
                sendFuture.setException(new IllegalStateException("BLE write request rejected"));
                sendFuture = null;
            }
        }
    }

    private boolean writeDescriptorCompat(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, byte[] value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS;
        }
        descriptor.setValue(value);
        return gatt.writeDescriptor(descriptor);
    }

    private boolean writeCharacteristicCompat(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS;
        }
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        characteristic.setValue(value);
        return gatt.writeCharacteristic(characteristic);
    }

    private void markClientReady(BluetoothGatt gatt) {
        if (clientReady) {
            return;
        }
        clientReady = true;
        emitState("BLE 客户端：已连接，可发送小包数据");
        emitLog("BLE 服务发现完成，写入已就绪");
        if (scanFuture != null) {
            scanFuture.set(gatt.getDevice());
            scanFuture = null;
        }
    }

    private void emitLog(String message) {
        if (listener != null) {
            listener.onLog(message);
        }
    }

    private void emitState(String state) {
        if (listener != null) {
            listener.onClientStateChanged(state);
        }
    }

    private String deviceName(BluetoothDevice device) {
        if (device == null) {
            return "unknown";
        }
        String name = device.getName();
        return name == null || name.trim().isEmpty() ? device.getAddress() : name;
    }

    private String bytesToHex(byte[] value) {
        if (value == null || value.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    public interface Listener {
        void onLog(String message);

        void onClientStateChanged(String state);
    }
}
