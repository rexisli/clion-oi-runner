# OI Runner

给 [CLion](https://www.jetbrains.com/clion/) 用的轻量插件：**一键构建并运行**当前正在看的 C/C++ 单文件，进程结束后在控制台打印**运行耗时、CPU 时间、峰值内存**——为 OI（信息学奥赛）本地对拍和卡常调试服务。

不接管编译，不接管调试，只做两件事：把 CLion 自己的「C++ 文件」运行配置绑到当前文件上，再在进程退出后补一行统计。

## 特性

- **跟随当前文件**：不用先去 Run 下拉框里挑配置，光标在哪个 `.cpp` 里就构建哪个。
- **复用 CLion 原生单文件构建**：编译、链接、工具链、代码补全与动态检查全部还是 CLion 自己的流程，插件不改任何工程设置。
- **退出后打印统计（Run 与 Debug 都测）**

  ```text
  -----------------------------
  Process finished with exit code 0
  Wall time: 1.0126 s | CPU time: 31.2500 ms | Peak memory: 4.41 MB
  ```

  - `Wall time`：进程实际存活时长（含被调度走的时间），取自内核记录的**创建时间到退出时间**，不是 IDE 的"开始/结束"事件时间差
  - `CPU time`：用户态 + 内核态的 CPU 时间，来自 `GetProcessTimes`
  - `Peak memory`：峰值工作集，来自 `GetProcessMemoryInfo` 的 `PeakWorkingSetSize`

  三个数字都是对**被运行的那个程序本体**取的，所以控制台窗口晚弹出来、或者程序用 `freopen` 重定向后不读标准输入，都不会污染 `Wall time`。
- **Release / Debug 两套编译选项**：CLion 的单文件运行配置只有一个编译选项字段，Run 和 Debug 天生共用一套。本插件把它拆成两个独立配置（`<文件名> [release]` / `<文件名> [debug]`），选项在设置里改，互不影响。
- **零依赖、零下载**：不引入任何第三方库，进程统计用 JDK 自带的 JNA 直调 Win32 API。

## 三个动作

右键菜单（编辑器内）或 `Tools` 菜单里可以找到：

| 菜单项 | 编译选项（默认） | 执行器 |
| --- | --- | --- |
| `OI: Run Current File (Release)` | `-O2 -DNDEBUG` | Run |
| `OI: Run Current File (Debug)` | `-O0 -g` | Run（不开调试器，用于对比耗时） |
| `OI: Debug Current File` | `-O0 -g` | Debug |

### 调试模式下的计时

调试时 CLion 交给插件的事件对应的是**调试器会话**（启动调试后端 → 加载程序 → 调试 → 收尾），
直接拿这个窗口当运行时长，会把调试器冷启动那 1~2 秒算进去，数字没有意义。

所以插件会在会话开始时按**可执行文件名**去调试器的子进程树里找真正被调试的程序（IDE 自己进程的子进程也会找，
必要时扫一遍全部进程），找到后用它的内核创建/退出时间计时，CPU 时间和峰值内存同样对它取。

控制台末尾会带一个标记，表示这个数字是怎么来的：

| 标记 | 含义 |
| --- | --- |
| 无标记 | 直接对程序本体取的内核时间，准确 |
| `[debug]` | 调试会话里定位到了被调试程序，数字同样是它本体的内核时间 |
| `[debug session]` | 没能在调试器树里定位到程序（例如程序太短、还没轮询到就退了），退化成整个调试会话的时长，只作粗略参考 |
| `[approximate]` | 进程句柄没抓到（极短命的程序），退化成 IDE 事件窗口的时长 |

不设断点、直接一路跑完的调试会话，也能看到 `[debug]` 那一行；注意它跑的是 `-O0 -g` 的构建，
和 Release 的数字不能直接对比。

编译选项在 **Settings → Tools → OI Runner** 里修改，改完下次运行即生效；插件只会同步它自己创建的那两个配置，不会碰你手写的配置。

## 安装

1. 从 [Releases](https://github.com/rexisli/clion-oi-runner/releases) 下载最新 `oi-runner.jar`
2. 放进 CLion 插件目录：

   ```text
   Windows: %APPDATA%\JetBrains\<产品名><版本>\plugins\oi-runner\lib\
   ```

   例如 `C:\Users\<你>\AppData\Roaming\JetBrains\CLion2026.1\plugins\oi-runner\lib\oi-runner.jar`
3. 重启 CLion

卸载：删掉对应的 `plugins\oi-runner` 目录后重启即可。

## 兼容性

| 项目 | 说明 |
| --- | --- |
| CLion | 2026.1（build `261`）实测可用；依赖的 `intellij.clion.runFile` 模块需为 `public` 可见性 |
| 系统 | Windows（统计依赖 kernel32 / psapi）；Linux / macOS 上插件仍可运行，但只输出带 `[approximate]` 的 `Wall time` |
| 编译器 | 任意 CLion 已配置的工具链（实测 MinGW GCC 13.1） |

## 从源码构建（无需联网、无需 Gradle）

本插件刻意不依赖 Gradle 与 IntelliJ Platform Gradle 插件：直接用 CLion 自带的 `lib/*.jar` 当 SDK，`javac` 编完打 jar 即可。

```bash
# 需要 JDK 21+（CLion 自带 JBR 或任意 Zulu/Temurin 都行）
export CLION_HOME="D:/CLion 2026.1.1"
export JAVAC="/c/Program Files/Zulu/zulu-25/bin/javac"

rm -rf out && mkdir -p out
"$JAVAC" --release 21 -encoding UTF-8 -d out -cp "$CLION_HOME/lib/*" $(find src -name '*.java')
mkdir -p out/META-INF && cp src/META-INF/plugin.xml out/META-INF/
jar cf oi-runner.jar -C out .
```

或直接跑 `./build.sh`。

## 已知限制

- 极短命的进程（存活 < 5 ms）可能在插件抓到句柄前就退出，此时 `Wall time` 退化成 IDE 事件窗口并带 `[approximate]`。
- `CPU time` 受 Windows 计时粒度影响，量化步长约 15.6 ms，因此小程序的数值会呈阶梯状。
- 调试会话里定位程序靠"可执行文件名匹配 + 进程启动时间不早于会话开始"，如果同时有另一个同名程序在跑，
  理论上可能认错；定位成功后插件会把 pid 写进 `idea.log`（`OI Runner: timing pid ...`）以便核对。
- 插件创建的是临时（temporary）运行配置，会随 CLion 的常规清理策略消失；重新点一次动作即可再建。

## 许可

[MIT](LICENSE)
