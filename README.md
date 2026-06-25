---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '73430270-b1e0-47af-9e45-f9e5c54e363d'
  PropagateID: '73430270-b1e0-47af-9e45-f9e5c54e363d'
  ReservedCode1: 'f73fbfa3-68ed-4bbb-afd6-1a5d270d6b69'
  ReservedCode2: 'f73fbfa3-68ed-4bbb-afd6-1a5d270d6b69'
---

# OpRecorder - Android 操作录制器

一个轻量级的 Android 操作录制与回放工具，支持录制屏幕操作、定时自动执行、息屏回放。

## 功能特性

### 操作录制
- **悬浮球控制**：点击展开菜单，一键开始/停止录制
- **覆盖层录制**：透明覆盖层捕获触摸坐标，支持点击、长按、滑动
- **导航键支持**：录制状态栏内置 HOME / BACK 按钮，可录制系统导航操作
- **操作间隔记录**：自动记录操作之间的时间间隔，回放时还原真实节奏

### 操作回放
- **无障碍手势注入**：通过 AccessibilityService 的 `dispatchGesture` 精准回放
- **回放状态栏**：显示回放进度，提供 HOME / BACK 导航按钮
- **循环回放**：可设置重复次数和循环间隔

### 定时调度
- **一次性**：指定时间执行一次
- **按周重复**：选择星期几，每周自动执行
- **自定义间隔**：按固定时间间隔循环执行
- **开机自启**：重启后自动恢复定时任务

### 息屏执行
- **WakeLock 保活**：CPU 不休眠，确保定时任务准时触发
- **自动亮屏**：回放前唤醒屏幕
- **自动解锁**：模拟上滑手势解除锁屏
- **屏幕常亮**：回放期间保持屏幕开启

## 支持的操作类型

| 类型 | 说明 |
|------|------|
| TAP | 点击指定坐标 |
| LONG_PRESS | 长按指定坐标（默认 500ms） |
| SWIPE | 从起点滑动到终点 |
| HOME | 模拟 Home 键 |
| BACK | 模拟返回键 |
| WAIT | 等待指定时长 |

## 环境要求

- Android 7.0 (API 24) 及以上
- Kotlin + AndroidX
- Room 数据库
- Gradle 8.5

## 所需权限

| 权限 | 用途 |
|------|------|
| SYSTEM_ALERT_WINDOW | 悬浮窗和录制覆盖层 |
| FOREGROUND_SERVICE | 前台服务保活 |
| WAKE_LOCK | 息屏时保持 CPU 运行 |
| SCHEDULE_EXACT_ALARM | 精确定时触发 |
| RECEIVE_BOOT_COMPLETED | 开机后恢复定时任务 |
| DISABLE_KEYGUARD | 唤醒并解锁屏幕 |
| POST_NOTIFICATIONS | 前台服务通知 (Android 13+) |

## 使用方法

### 1. 安装与配置

1. 编译 APK 或下载 Release 版本
2. 安装到手机
3. 开启**无障碍服务**：设置 → 无障碍 → OpRecorder → 开启
4. 开启**悬浮窗权限**：设置 → 应用 → OpRecorder → 悬浮窗 → 允许
5. 开启**通知权限**（Android 13+）
6. 如需定时功能，开启**精确定时闹钟**权限
7. 如需开机自启，开启**开机自启动**权限

### 2. 录制操作

1. 点击悬浮球展开菜单
2. 选择「开始录制」
3. 正常操作手机，录制状态栏会显示 `● REC`
4. 操作完成后，通过悬浮球菜单「停止录制」
5. 为脚本命名并保存

### 3. 回放操作

- **手动回放**：在主界面选择脚本，点击播放按钮
- **悬浮球回放**：点击悬浮球 → 播放上次脚本
- **编辑操作**：可调整每个操作的坐标、时长和延迟

### 4. 定时执行

1. 在脚本详情页点击「设置定时」
2. 选择触发方式：一次性 / 按周 / 自定义间隔
3. 设置触发时间
4. 即使息屏也会自动唤醒屏幕并执行

## 项目结构

```
app/src/main/java/com/oprecorder/
├── App.kt                          # Application 入口
├── MainActivity.kt                 # 主界面：脚本列表
├── ScriptDetailActivity.kt         # 脚本详情：操作列表 + 定时设置
├── data/
│   ├── AppDatabase.kt              # Room 数据库 (version 2)
│   ├── Script.kt                   # 脚本实体
│   ├── ScriptAction.kt             # 操作动作实体
│   └── ScriptDao.kt                # 数据访问对象
├── service/
│   ├── OpAccessibilityService.kt   # 无障碍服务：手势回放 + 息屏唤醒
│   ├── FloatingControlService.kt   # 悬浮球 + 录制覆盖层 + 状态栏
│   └── SchedulerService.kt         # 定时调度服务
├── receiver/
│   ├── AlarmReceiver.kt            # 闹铃触发器
│   └── BootReceiver.kt             # 开机自启
├── util/
│   └── ScheduleManager.kt          # 定时管理：触发时间计算 + 按周调度
└── ui/
    ├── ScriptAdapter.kt            # 脚本列表适配器
    └── ActionAdapter.kt            # 操作列表适配器
```

## 构建

```bash
# Debug APK
gradle assembleDebug

# Release APK
gradle assembleRelease
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

## 技术实现要点

- **录制方案**：优先尝试 `onMotionEvent`，若系统不回调则自动降级为透明覆盖层方案
- **覆盖层转发**：录制时覆盖层设为 `FLAG_NOT_TOUCHABLE` 放行触摸事件，同时通过 `onTouchEvent` 捕获坐标；需要注入手势时临时切换为可触摸，完成后立即恢复
- **息屏回放**：通过 `PowerManager.WakeLock` 唤醒 CPU → `ACQUIRE_CAUSES_WAKEUP` 点亮屏幕 → 模拟上滑解锁 → 设置 `FLAG_KEEP_SCREEN_ON` 保持常亮
- **定时调度**：`AlarmManager` 精确闹钟 + `BroadcastReceiver` 触发 + `ForegroundService` 保活
- **数据库**：Room + KSP 编译，`fallbackToDestructiveMigration` 处理升级

## License

MIT

> AI生成