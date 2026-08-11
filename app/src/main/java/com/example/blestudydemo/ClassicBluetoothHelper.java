package com.example.blestudydemo;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 经典蓝牙 RFCOMM 文件传输工具类。
 *
 * <p>经典蓝牙需要设备先完成系统配对。发送端通过 RFCOMM Socket 连接接收端，
 * 传输格式是：文件名 UTF、文件大小 long、文件内容字节流。</p>
 */
public class ClassicBluetoothHelper {
    public static final UUID FILE_UUID = UUID.fromString("8b2f0b46-9e0d-4f24-bd3c-1f1a62f6dd31");

    private static final int BUFFER_SIZE = 16 * 1024;

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final Listener listener;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private BluetoothServerSocket serverSocket;
    private Thread serverThread;

    public ClassicBluetoothHelper(Context context, BluetoothAdapter bluetoothAdapter, Listener listener) {
        this.context = context.getApplicationContext();
        this.bluetoothAdapter = bluetoothAdapter;
        this.listener = listener;
    }

    public Future<List<BluetoothDevice>> getPairedDevicesAsync() {
        return executor.submit(() -> {
            List<BluetoothDevice> devices = new ArrayList<>();
            if (bluetoothAdapter != null) {
                Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
                if (bonded != null) {
                    devices.addAll(bonded);
                }
            }
            emitLog("经典蓝牙配对设备数量：" + devices.size());
            return devices;
        });
    }

    public Future<Void> startFileServer() {
        SimpleFuture<Void> future = new SimpleFuture<>();
        if (bluetoothAdapter == null) {
            future.setException(new IllegalStateException("BluetoothAdapter is null"));
            return future;
        }
        stopFileServer();
        serverThread = new Thread(() -> {
            try {
                // listenUsingRfcommWithServiceRecord 会创建类似串口服务的 ServerSocket。
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("BleStudyDemoFile", FILE_UUID);
                emitLog("经典蓝牙文件接收端已启动，等待连接");
                future.set(null);
                while (!Thread.currentThread().isInterrupted()) {
                    // accept 是阻塞调用；停止服务时关闭 serverSocket 会让它退出。
                    BluetoothSocket socket = serverSocket.accept();
                    receiveFile(socket);
                }
            } catch (IOException e) {
                if (!future.isDone()) {
                    future.setException(e);
                }
                emitLog("经典蓝牙接收端停止：" + e.getMessage());
            }
        }, "classic-file-server");
        serverThread.start();
        return future;
    }

    public Future<Void> stopFileServerAsync() {
        return executor.submit(() -> {
            stopFileServer();
            return null;
        });
    }

    public void stopFileServer() {
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
    }

    public Future<Long> sendFile(BluetoothDevice device, Uri fileUri) {
        return executor.submit(() -> {
            if (device == null) {
                throw new IllegalArgumentException("device is null");
            }
            if (fileUri == null) {
                throw new IllegalArgumentException("fileUri is null");
            }
            try (InputStream input = context.getContentResolver().openInputStream(fileUri);
                 BluetoothSocket socket = device.createRfcommSocketToServiceRecord(FILE_UUID)) {
                if (input == null) {
                    throw new IOException("文件无法打开");
                }
                bluetoothAdapter.cancelDiscovery();
                socket.connect();
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                String name = fileUri.getLastPathSegment() == null ? "picked-file" : fileUri.getLastPathSegment();
                long size = queryFileSize(fileUri);
                // 先写元数据，接收端据此决定保存文件名和读取多少字节。
                output.writeUTF(name);
                output.writeLong(size);
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                long sent = 0;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    sent += read;
                }
                output.flush();
                emitLog("经典蓝牙文件发送完成：" + sent + " bytes -> " + deviceName(device));
                return sent;
            } catch (IOException e) {
                emitLog("经典蓝牙发送失败：" + e.getMessage());
                throw e;
            }
        });
    }

    public void release() {
        stopFileServer();
        executor.shutdownNow();
    }

    private void receiveFile(BluetoothSocket socket) {
        try (BluetoothSocket closeableSocket = socket;
             DataInputStream input = new DataInputStream(closeableSocket.getInputStream())) {
            // 必须和发送端协议顺序一致：文件名、文件大小、文件内容。
            String name = input.readUTF();
            long size = input.readLong();
            if (size <= 0) {
                emitLog("经典蓝牙收到无效文件大小，取消接收：" + size);
                return;
            }
            File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                dir = context.getFilesDir();
            }
            File outFile = new File(dir, "received_" + System.currentTimeMillis() + "_" + sanitizeFileName(name));
            try (FileOutputStream output = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long copied = 0;
                while (copied < size) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, size - copied));
                    if (read == -1) {
                        break;
                    }
                    output.write(buffer, 0, read);
                    copied += read;
                }
                emitLog("经典蓝牙收到文件：" + outFile.getAbsolutePath() + " (" + copied + "/" + size + " bytes)");
            }
        } catch (IOException e) {
            emitLog("经典蓝牙接收文件失败：" + e.getMessage());
        }
    }

    private long queryFileSize(Uri uri) {
        try {
            ContentResolver resolver = context.getContentResolver();
            try (android.database.Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (index >= 0) {
                        return cursor.getLong(index);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (descriptor != null && descriptor.getLength() > 0) {
                return descriptor.getLength();
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String deviceName(BluetoothDevice device) {
        if (device == null) {
            return "unknown";
        }
        String name = device.getName();
        return name == null || name.trim().isEmpty() ? device.getAddress() : name;
    }

    private void emitLog(String message) {
        if (listener != null) {
            listener.onLog(message);
        }
    }

    public interface Listener {
        void onLog(String message);
    }
}
