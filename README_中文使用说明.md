# H10 Research Recorder v3.1

这是一个独立的研究记录示例应用，基于 Polar BLE SDK 5.1.0 构建，可用于兼容设备的 ECG、HR 和 RR 数据采集。

> 非官方声明：本项目不是 Polar 官方产品，也未获 Polar 赞助、认可或背书。“Polar”和“H10”是 Polar Electro Oy 的商标，仅用于说明 SDK 依赖和兼容性。

## 许可证与归属

- Polar BLE SDK 及其相关材料适用其原始许可证；完整副本见项目根目录的 [Polar_SDK_License.txt](Polar_SDK_License.txt)。
- 保留源码及 SDK 材料中所有原始版权和许可声明。
- 本项目仅提供应用层示例代码；使用者须自行确认其使用方式符合 Polar SDK 许可证、适用法律和研究伦理要求。

## 3.0 版的核心变化

- 将 Polar BLE 连接、ECG/HR/RR 数据流和 CSV 写入全部移入 Android 前台服务。
- 录制开始后可以：
  - 锁屏；
  - 按 Home；
  - 切换到其他 App；
  - 从 ECG 显示页面返回；
  - 从最近任务界面离开应用页面。
- 前台服务通过常驻通知显示当前状态，并提供“Stop & save”。
- 正在录制时持有 Android partial wake lock，防止锁屏后 CPU 休眠中断写入。
- 真实 BLE 断线时不立即关闭 CSV，而是写入事件并自动重连；重连后继续写入同一组文件。
- CSV 新增 `connection_id`，用于区分断线前后的数据段。
- 页面只负责显示，销毁页面不会再关闭 Polar SDK。

## 原始 ECG 数据承诺

`ecg.csv` 中的 `ecg_uV` 是 Polar SDK 返回的 `sample.voltage` 原始整数值。

应用内不会对 ECG 执行：

- 滤波；
- 去基线漂移；
- 平滑；
- 归一化；
- 重采样；
- 插值；
- 伪迹修复；
- R 峰检测；
- HRV 计算。

界面绘图时会临时把微伏除以 1000 转为毫伏，仅用于显示，不影响保存到 CSV 的原始值。

## 安装

1. 在 Android Studio 中打开整个项目文件夹。
2. Gradle JDK 选择 JDK 17。
3. 等待 Gradle Sync 完成。
4. 连接安卓手机并运行 `app`。
5. 首次运行时允许：
   - 附近设备／蓝牙；
   - 通知。

3.0 的 `versionCode` 高于 2.1，正常情况下会覆盖旧版安装。

## 正式使用前必须设置

### 1. 允许通知

Android 13 及以上请允许通知。前台录制服务会显示常驻通知。

### 2. 关闭此 App 的电池优化

在主页面点击：

`OPEN BATTERY OPTIMIZATION SETTINGS`

找到 `H10 Research Recorder`，将其设为“不优化”“无限制”或允许后台活动。不同品牌手机的名称可能不同。

### 3. 不要这样操作

录制中不要：

- 在系统设置中对 App 执行“强制停止”；
- 使用手机管家清理该 App；
- 重启或关机；
- 关闭蓝牙；
- 打开 Polar Beat/Flow 抢占 H10；
- 在安卓系统蓝牙列表中手动配对 H10。

锁屏和普通切换 App 是支持的；“强制停止”会被 Android 系统直接终止，任何 App 都无法绕过。

## 录制流程

1. 实际佩戴并湿润 Polar H10 胸带。
2. 打开 App。
3. 输入 Participant ID，例如 `P001`。
4. Device ID 填写你自己的设备 ID，例如 `YOUR_H10_DEVICE_ID`；不要将真实设备 ID 提交到版本控制。
5. 点击 `OPEN ECG + HR/RR RECORDER`。
6. 等待状态变为：

   `Connected; ECG and HR/RR streams active`

7. 点击 `START RECORDING`。
8. 此时可以锁屏或切换其他 App；常驻通知会显示录制状态。
9. 实验结束有两种停止方法：
   - 回到 App 点击 `STOP & SAVE`；
   - 在通知栏点击 `Stop & save`。
10. 不再使用时点击 `DISCONNECT / CLOSE BACKGROUND SERVICE`。

## 文件位置

`Documents/PolarExperiment`

每次录制生成：

- `P001_日期时间_ecg.csv`
- `P001_日期时间_hr_rr.csv`
- `P001_日期时间_events.csv`

## ECG 文件主要字段

- `connection_id`：本次 BLE 连接段编号；重连后会增加。
- `packet_index`：ECG 数据包序号。
- `sample_index`：全局 ECG 样本序号。
- `packet_received_unix_ns`：手机收到该数据包的 Unix 纳秒时间。
- `sensor_timestamp_ns`：Polar SDK 返回的原始传感器时间戳。
- `estimated_sample_unix_ns`：将传感器相对时间锚定到手机 Unix 时钟后的估算时间。
- `timestamp_source`：使用传感器时间或固定 130 Hz 回退时间。
- `ecg_uV`：未经处理的原始 ECG 微伏值。

## HR/RR 文件主要字段

- `connection_id`
- `packet_index`
- `hr_bpm`
- `rr_available`
- `rr_ms`
- `packet_received_unix_ns`
- `estimated_beat_unix_ns`

RR 仍保留 v2.1 的精确重复抑制：只跳过 SDK 5.1.0 在 100 ms 内重复返回的完全相同 HR/RR 样本。该逻辑不作用于 ECG。

## events 文件

可能包含：

- `RECORDING_STARTED`
- `RECORDING_CONNECTION_CONTEXT`
- `DEVICE_CONNECTED`
- `DEVICE_DISCONNECTED`
- `RECONNECT_SCHEDULED`
- `CONNECT_TIMEOUT`
- `ECG_STREAM_ERROR`
- `HR_RR_STREAM_ERROR`
- `APP_TASK_REMOVED`
- `RECORDING_STOPPED`

分析时不要直接跨不同 `connection_id` 假设数据完全连续。必须结合 `events.csv` 检查缺口。

## 建议的验收测试

正式实验前进行一次至少 30 分钟测试：

1. 开始记录后保持前台 5 分钟；
2. 锁屏 10 分钟；
3. 解锁并切换其他 App 10 分钟；
4. 回到 Recorder 页面再记录 5 分钟；
5. 停止并导出。

检查：

- ECG 样本率接近 130 Hz；
- `sample_index` 连续递增；
- 锁屏期间仍有 ECG 和 RR；
- `events.csv` 没有意外 `SERVICE_DESTROYED`；
- 若没有真实断线，`connection_id` 应保持不变；
- 停止后三个 CSV 均可正常打开。

## Android 系统限制

前台服务、常驻通知和 partial wake lock 能显著提高锁屏及后台记录稳定性，但不能绕过用户主动“强制停止”、关机、系统极端内存回收或厂商特别激进的后台清理。因此正式实验前必须在实际使用的手机型号上完成长时间验收。


## v3.1：手动强制重连

录制页面新增 `FORCE RECONNECT H10`。

如果状态持续停在 `Connecting...`、`Disconnected; reconnecting...`，或者连接已经恢复但 ECG/HR/RR 数据流没有恢复，可以点击这个按钮。

它会执行一次“硬重连”：

1. 取消旧的连接 watchdog 和自动重连任务；
2. 停止 ECG 与 HR/RR subscriptions；
3. 主动断开当前 H10 会话；
4. 完整关闭当前 PolarBleApi；
5. 创建新的 PolarBleApi；
6. 等待约 1.5 秒；
7. 再次连接当前 H10。

如果正在记录：
- CSV 文件保持打开；
- 不会丢弃已经保存的数据；
- 重连期间会出现真实的数据缺口；
- `events.csv` 写入 `FORCE_RECONNECT_REQUESTED`；
- 重连成功后 `connection_id` 增加，方便后期识别重连边界。

通知栏也新增 `Reconnect` 操作，因此锁屏时也能手动重连。

刚打开 App 时可以先等待约 20–30 秒；如果仍停在 Connecting，再使用 FORCE RECONNECT。
