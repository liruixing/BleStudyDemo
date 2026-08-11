# BLE Study Demo

一个用于学习 Android 蓝牙通信的最小 demo，包含：

- BLE 低功耗数据传输：GATT Server、广播、扫描、连接、Characteristic 写入、Notify 回包、20 bytes 分包演示。
- 经典蓝牙文件传输：RFCOMM ServerSocket 接收端、Socket 客户端、文件选择器、流式读写。
- 同一个 APK 同时支持客户端和服务端模式，适合两台 Android 设备互测。

## 运行

```bash
./gradlew assembleDebug
```

生成 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## BLE 测试

1. 两台设备都安装 APK，并授予蓝牙权限。
2. 设备 A 点击「启动 BLE 服务端」。
3. 设备 B 点击「扫描并连接」。
4. 设备 B 修改输入框内容后点击「发送 BLE 数据」。
5. 设备 A 日志会显示收到的分包，设备 B 会收到 `ack total=...` 通知。

源码入口：`app/src/main/java/com/example/blestudydemo/MainActivity.java`

关键 UUID：

- Service: `0000a100-0000-1000-8000-00805f9b34fb`
- Characteristic: `0000a101-0000-1000-8000-00805f9b34fb`
- CCCD: `00002902-0000-1000-8000-00805f9b34fb`

## 经典蓝牙文件测试

1. 在系统蓝牙设置中先配对两台设备。
2. 设备 A 点击「启动文件接收端」。
3. 设备 B 点击「刷新配对设备」，选择设备 A。
4. 设备 B 点击「选择文件」，再点击「发送文件」。
5. 设备 A 收到的文件保存在 app 私有下载目录：

```text
Android/data/com.example.blestudydemo/files/Download/
```

经典蓝牙使用固定 RFCOMM UUID：

```text
8b2f0b46-9e0d-4f24-bd3c-1f1a62f6dd31
```

## 学习点

- BLE 适合低功耗、小数据、状态同步和控制指令，不适合大文件。
- BLE GATT 写入需要关注 MTU 和分包，本 demo 用默认 20 bytes 分包展示基础模型。
- Notify 需要客户端启用 CCCD 描述符。
- 经典蓝牙 RFCOMM 更像串口 socket，适合连续字节流和文件传输，但功耗更高。
- Android 12+ 需要 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`BLUETOOTH_ADVERTISE` 运行时权限。
