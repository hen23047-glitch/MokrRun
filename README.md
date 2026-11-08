# 随心跑 (MokrRun)

体适能 / 阳光跑 的轨迹与步数模拟模块。LSPosed 模块 + 独立控制 App。

- 模块 App：`com.dsh.mokr`（UI + 配置 + 悬浮窗）
- 注入目标：`com.bxkj.student`（体适能）
- 探针夹具：`com.dsh.mokr.probe`（独立工程，`tools/probe/`）

> **许可**：MIT，见 [LICENSE](LICENSE)。本项目仅供学习 LSPosed / Android 定位与传感器 API 的 hook 技术使用。

---

## 一、它现在能做什么

| 功能 | 状态 | 验证方式 |
|---|---|---|
| 地图绘制路线（高德瓦片，免 SDK） | ✅ | 手机上画 6 点 / 519 m，保存后首页显示 |
| 参数配置（速度 / 步频 / 步长 / 检测绕过） | ✅ | 改值后写进 `Settings.Global`，hook 读到 |
| 位置伪造 | ✅ | `getLastKnownLocation` 四个 provider 全部返回合成坐标 |
| 位置沿路线推进 | ✅ | 日志连续采样：经纬度同时变化，5 秒 15 m |
| 步数与位置同源 | ✅ | 10.3 秒 22 步（2.14 步/秒），与 3 m/s 同步 |
| 悬浮窗控制 | ✅ | 可拖动，开始/暂停写 `mokr_run`，引擎即时响应 |
| 反检测 | ✅ | `Location.isMock` / `isFromMockProvider` 恒 false，模拟定位类 app 从包列表隐藏 |
| 首页状态联动 | ✅ | 显示「运行中 / 已就绪 / 待下发」 |

---

## 二、架构

```
┌────────────────────┐                      ┌──────────────────────────┐
│  随心跑 App         │   Settings.Global    │  体适能进程 (hook)        │
│  com.dsh.mokr      │ ───────────────────→ │                          │
│                    │                      │  MotionEngine            │
│  MainActivity      │  mokr_config         │   ├ 250ms tick           │
│   ├ 首页/路线/设置  │  speed=3.0;...       │   ├ 沿路线插值推进         │
│   ├ MapActivity    │                      │   └ 按 cadence 产步数      │
│   └ DeveloperActivity                     │                          │
│                    │  mokr_route_b64      │  LocationProbe           │
│  FloatService      │  base64(路线 JSON)   │   └ 替换所有位置读取       │
│   └ 悬浮窗          │                      │                          │
│                    │  mokr_run            │  SensorHook              │
│  ConfigSync        │  1 / 0               │   └ 注入 step_counter    │
│   └ 写 Global      │                      │                          │
└────────────────────┘                      │  AntiDetect              │
                                            │   └ mock 标记恒 false    │
                                            └──────────────────────────┘
```

### 为什么用 Settings.Global

试过并放弃的四条通道：

| 通道 | 失败原因 |
|---|---|
| `XSharedPreferences` | Android 14 + LSPosed 2.2 读不到（返回默认值） |
| `ContentProvider` | MIUI 拦跨应用访问，`query` 返回 null |
| 显式广播 | `process is not permitted to auto start`（MIUI 自启动限制） |
| root 写文件 | `su` 不在 App 可访问路径（SukiSU 只对白名单开放） |

`Settings.Global` 是唯一两条路都通的：**读端零权限**（hook 侧直接用），**写端一次 adb 授权**即可。

> 格式刻意做成无引号：`speed=3.0;cadence=2.2;stride=1.1;anti=1`
> 路线太大且含引号，单独走 `mokr_route_b64`（base64）。

---

## 三、构建

无 Gradle，手工流水线：

```
aapt2 compile → aapt2 link → javac → d8 → zip → zipalign → apksigner
```

```powershell
# 主模块
C:\MokrRun\build.ps1

# 探针
C:\MokrRun\tools\probe\build-probe.ps1

# 安装（自动处理 MIUI 两层确认框）
C:\MokrRun\install.ps1
```

工具链：

| 组件 | 位置 | 备注 |
|---|---|---|
| JDK 21 | `C:\mokrtools\jdk` | Temurin |
| build-tools 34 | `C:\mokrtools\bt\android-14` | aapt2 / zipalign / apksigner |
| **r8 9.5.29** | `C:\mokrtools\r8\r8.jar` | 关键：build-tools 自带的 d8 8.2.2 遇到内部类/匿名类必崩 |
| android.jar | `C:\mokrtools\android.jar` | API 34 |

> 中文路径会让 aapt2 / d8 失败，因此用 junction：
> `C:\MokrRun` → `手机\MokrRun`，`C:\mokrtools` → `手机\build\tools`

版本号自动递增（`version.txt`），构建时注入 manifest 并生成 `BuildConfigTag.java`，界面上能看到当前装的是哪一版。

---

## 四、首次部署清单

1. **授予配置写入权限**（一次性）：
   ```powershell
   adb shell pm grant com.dsh.mokr android.permission.WRITE_SECURE_SETTINGS
   ```

2. **授予悬浮窗权限**：
   ```powershell
   adb shell appops set com.dsh.mokr SYSTEM_ALERT_WINDOW allow
   ```

3. **在 LSPosed 中启用模块并勾选体适能**（若管理器进不去，可直接改配置库）：
   ```powershell
   # 导出 /data/adb/lspd/config/modules_config.db，插入：
   #   modules       : (com.dsh.mokr, apk_path)
   #   modules_state : (com.dsh.mokr, 0, 1, 0)
   #   scope         : (com.dsh.mokr, com.bxkj.student, 0)
   # 推回并清掉 -wal/-shm，然后重启
   ```

4. **关闭 MIUI 对体适能的省电限制**（否则后台被杀会中断模拟）

---

## 五、使用流程

1. 打开 **随心跑** → 「路线」→ 新建路线 → 地图上点几个点 → 保存
2. 「设置」里调速度（默认 3.0 m/s）、步频（默认 2.2 步/秒）
3. 回首页 → 「开始模拟」→ 悬浮窗出现，App 自动退到后台
4. 切到 **体适能**，进入跑步页面
5. 点悬浮窗「开始」，位置和步数开始同步推进

---

## 六、已知限制

| 限制 | 说明 |
|---|---|
| **探针不被注入** | `com.dsh.mokr.probe` 已写入 LSPosed 的 scope 表且格式与其它模块一致，但 LSPosed 完全不尝试注入（logcat 无任何相关日志），重启无效。暂时用体适能 + logcat 验证 |
| **依赖一次 adb 授权** | `WRITE_SECURE_SETTINGS` 必须用 adb 授予，重启不丢失 |
| **MIUI 自启动限制** | 广播唤醒模块 App 会被拦，因此 hook 侧无法主动向 App 请求配置；改成 App 主动下发 |
| **默认两条直线路线** | 若从未画过路线，引擎用内置两点直线（23.0678,113.9714 → 23.0695,113.9714） |
| **人脸检测** | 不做绕过，由用户本人通过 |
| **仅主进程注入** | `pushcore` 子进程跳过，避免引擎跑两份 |

---

## 七、体适能判定条件（供调参参考）

- 距离 ≥ 2.0 km，时间 5–16 分钟，步数 ≥ 1200，上限 5.0 km
- 必经点位：田径场大灯2、A1宿舍楼、田径场大灯、1号教学楼南侧
- 随机人脸检测 2 次
- 每天只算一次有效

按默认参数 3.0 m/s + 2.2 步/秒：跑 2 km 需要约 11 分钟、2600 步，落在判定区间内。

---

## 八、日志

```powershell
adb logcat -v time -s MokrRun:* XposedBridge:* | Select-String "MokrRun"
```

App 内「设置 → 开发者 → 复制 adb 抓日志命令」可以一键复制这条命令。

常见日志：

```
MokrRun: attached to com.bxkj.student process=... main=true
MokrRun: config from Settings.Global speed=3.0 cadence=2.2 stride=1.1 routeLen=288
MokrRun: route loaded, 6 points, first=23.0692606,113.9713943
MokrRun: tick dist=15.8m steps=11 pos=23.067942,113.971400
MokrRun: anti-detect hooked Location.isMock -> false
```
