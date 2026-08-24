# McMusic 逆向工程复盘

> 记录如何逆向 Minecraft 26.2 的 Toast 系统 + macOS MediaRemote 私有 API，以及踩过的坑。

## 1. 反编译 Minecraft 26.2

### 步骤

```bash
# 1. 找到 deobf jar（Loom 缓存）
ls ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/

# 2. 提取关键 class
mkdir /tmp/mcmc-extract
cd /tmp/mcmc-extract
unzip ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft/26.2/minecraft-merged-deobf-26.2.jar \
  'net/minecraft/client/gui/components/toasts/*.class' \
  'net/minecraft/client/gui/screens/PauseScreen.class' \
  'net/minecraft/client/Options.class' \
  'net/minecraft/client/MusicToastDisplayState.class'

# 3. 查看字节码（反编译）
javap -p -c NowPlayingToast.class    # 方法签名 + 字节码
javap -p Toast.class                 # 接口定义
javap -p ToastManager.class          # 管理器

# 4. 查看常量值（DEFAULT_WIDTH, SLOT_HEIGHT 等）
javap -p -v Toast.class | grep -A 2 "public static final int"
```

### 关键发现

| 发现 | 位置 | 影响 |
|------|------|------|
| `Toast.yPos(int)` 默认 = `firstSlotIndex * this.height()` | Toast.java:36-38 | 用 toast 自己的 height，不是 SLOT_HEIGHT(32) |
| `NowPlayingToast.yPos(int)` = `0.0f`（硬编码） | NowPlayingToast.java:110-112 | 游戏音乐永远在 y=0 |
| `nowPlayingToast` 是私有单例字段 | ToastManager.java:32 | 无法扩展，只能 NEVER 关闭 |
| `Options.musicToast` setter 有 listener | Options.java:961 | `set(NEVER)` 会同步清 nowPlayingToast |
| `ToastManager.extractRenderState` 有两条渲染路径 | ToastManager.java:81-98 | visibleToasts 在所有屏幕渲染；nowPlayingToast 只在非 PauseScreen |
| `PauseScreen.extractRenderState` 调用 `NowPlayingToast.extractToast` 静态方法 | PauseScreen.java:216 | 暂停菜单的游戏音乐 toast 是独立渲染路径 |
| `MusicToastDisplayState.renderToast()` = `== PAUSE_AND_TOAST` | MusicToastDisplayState.java:39-41 | 只有 PAUSE_AND_TOAST 才在 HUD 显示 toast |
| `MusicToastDisplayState.renderInPauseScreen()` = `!= NEVER` | MusicToastDisplayState.java:35-37 | PAUSE 和 PAUSE_AND_TOAST 都在暂停菜单显示 |
| `Toast.SLOT_HEIGHT` = 32, `DEFAULT_WIDTH` = 160 | Toast.java:14-15 | 常量值需 `javap -v` 才能看到 |

### 逆向技巧

- `javap -p -c` 看字节码（方法体）
- `javap -p -v` 看常量值（`ConstantValue` 属性）
- `javap -p -c ToastManager\$ToastInstance.class` 看内部类
- 用 `grep -n` 在反编译输出里搜索关键方法名
- 找 `sipush`/`bipush` 指令定位常量值（`sipush 160` = DEFAULT_WIDTH）

## 2. macOS MediaRemote 私有 API（尝试 + 失败）

### 环境
- macOS 26.6.1 (25G76), ARM64
- swiftc 6.3.3 可用
- `/System/Library/PrivateFrameworks/MediaRemote.framework` 存在但**无二进制**（只有 Resources）

### dlsym 验证
```c
void *h = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_LAZY);
// dlopen 成功，handle 非 NULL
void *sym = dlsym(h, "MRMediaRemoteRegisterForNowPlayingNotifications");
// dlsym 成功，sym 非 NULL（符号在 dyld shared cache 里）
```

### 编译 ObjC helper
```bash
clang -fobjc-arc -framework Foundation \
  -F/System/Library/PrivateFrameworks -framework MediaRemote \
  -rpath /System/Library/PrivateFrameworks \
  helper.m -o helper
```
编译成功，链接成功。

### 运行崩溃
```
EXC_BAD_ACCESS (SIGSEGV), KERN_INVALID_ADDRESS at 0x0000000000000020
Thread 0 Crashed:
0  objc_retain + 16
1  MRMediaRemoteRegisterForNowPlayingNotifications + 36
2  main + 36
```

**根因**：`MRMediaRemoteRegisterForNowPlayingNotifications` 内部访问 `MRMediaRemoteServiceClient`，在 macOS 26 上 service client 未初始化 → retain nil → SIGSEGV。苹果在 macOS 26 改了内部实现，需要先创建 service client。

### 结论
MediaRemote 私有 API 在 macOS 26 上**不可直接使用**（RegisterForNowPlayingNotifications 崩溃）。需要找到正确的初始化序列（可能需要 `MRMediaRemoteCreateServiceClient` 或类似函数），但这属于深度逆向，超出当前工程范围。

### Fallback
轮询间隔从 300ms 降到 150ms。虽然不是真毫秒级，但在纯 Java + AppleScript 限制下是最实际的方案。

## 3. macOS AppleScript 的坑

### `tell application "X"` 会自动启动 app
```applescript
tell application "VLC"  -- 如果 VLC 没运行，这会启动它！
```

### `application "X" is running` 仍可能触发 Launch Services
```applescript
if application "VLC" is running then  -- 某些 macOS 配置下仍会启动 VLC
```

### 唯一安全方案：System Events 查进程
```applescript
tell application "System Events"
    if not (exists process "VLC") then return ""
end tell
tell application "VLC"  -- 此时 VLC 已确认运行，不会重复启动
    ...
end tell
```

### 可靠性排序
1. `System Events exists process` — 绝对不启动，最慢
2. `application "X" is running` — 通常不启动，但有 edge case
3. `tell application "X"` — 必启动

## 4. Toast 位置抖动 bug

### 症状
toast 跑到第三行，后来又回到第二行。

### 根因
`CustomMusicToast.yPos()` 每帧查 `getCurrentMusicTranslationKey()`。游戏音乐在切歌间隙（key 短暂为 null）→ baseOffset 从 30 变 0 → toast 跳到第一行 → 下一帧 key 恢复 → baseOffset 回 30 → toast 跳回第二行。

### 修复
加 2 秒缓存：key 变 null 后 2 秒内仍认为"在播"，吸收切歌间隙。

```java
private static long gameMusicLastSeenMs = 0;

private static boolean isInGameMusicPlaying() {
    String key = mc.getMusicManager().getCurrentMusicTranslationKey();
    long now = System.currentTimeMillis();
    if (key != null) gameMusicLastSeenMs = now;
    return key != null || (now - gameMusicLastSeenMs < 2000);
}
```

## 5. 暂停菜单真·常驻

### 方案
在 `Toast.update()` 里判断当前屏幕是否为 PauseScreen，是则不设置 HIDE：

```java
public void update(ToastManager manager, long time) {
    Minecraft mc = Minecraft.getInstance();
    boolean inPauseMenu = mc != null && mc.gui.screen() instanceof PauseScreen;
    if (!inPauseMenu && time >= config.displayDurationMs) {
        visibility = Visibility.HIDE;
    }
    tickMusicNotes();
}
```

这复刻了原版 NowPlayingToast 的双行为：HUD transient + 暂停菜单常驻。不需要 Mixin 侵入 ToastManager。

## 6. 完整逆向流程清单

1. `unzip` deobf jar 提取 class
2. `javap -p -c` 看方法体字节码
3. `javap -p -v` 看常量值
4. `grep` 搜索关键方法/字段名
5. 对照 Mojang mappings（Loom 自动 remap）确认可读名
6. 对私有字段用 `@Shadow` + `@Accessor`
7. 对私有方法用 `@Redirect` / `@Inject`
8. 编译验证 `gradle build --no-daemon`
9. 运行时验证（Mixin 注入失败会 crash）

## 7. 未解决的天花板

| 能力 | 状态 | 原因 |
|------|------|------|
| macOS 毫秒级事件驱动 | ❌ | MediaRemote `RegisterForNotifications` 在 macOS 26 崩溃 |
| macOS 多来源枚举 | ❌ | 私有 MediaRemote API 崩溃 |
| 完美夺舍原版单例 | ⚠️ | 可行但每 MC 版本需重新逆向，脆弱 |
| Windows/Linux 多来源 | ✅ | SMTC GetSessions / playerctl -a |
| 暂停菜单常驻 | ✅ | update() 判断屏幕 |
| 位置抖动 | ✅ | 2 秒缓存 |
