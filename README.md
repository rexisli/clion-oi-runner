# OI Runner

给 [CLion](https://www.jetbrains.com/clion/) 用的轻量插件：**一键构建并运行**当前正在看的 C/C++ 单文件，进程结束后在控制台打印**运行耗时、CPU 时间、峰值内存**——为 OI（信息学奥赛）本地对拍和卡常调试服务。

不接管编译，不接管调试，只做两件事：把 CLion 自己的「C++ 文件」运行配置绑到当前文件上，再在进程退出后补一行统计。

## 特性

- **跟随当前文件**：不用先去 Run 下拉框里挑配置，光标在哪个 `.cpp` 里就构建哪个。
- **复用 CLion 原生单文件构建**：编译、链接、工具链、代码补全与动态检查全部还是 CLion 自己的流程，插件不改任何工程设置。
- **退出后打印统计**

  ```text
  -----------------------------
  Process finished with exit code 0
  Wall time: 1.0126 s | CPU time: 31.2500 ms | Peak memory: 4.41 MB
  ```

  - `Wall time`：进程实际存活时长（含被调度走的时间）
  - `CPU time`：用户态 + 内核态的 CPU 时间，来自 `GetProcessTimes`
  - `Peak memory`：峰值工作集，来自 `GetProcessMemoryInfo` 的 `PeakWorkingSetSize`
- **Release / Debug 两套编译选项**：CLion 的单文件运行配置只有一个编译选项字段，Run 和 Debug 天生共用一套。本插件把它拆成两个独立配置（`<文件名> [release]` / `<文件名> [debug]`），选项在设置里改，互不影响。
- **零依赖、零下载**：不引入任何第三方库，进程统计用 JDK 自带的 JNA 直调 Win32 API。

## 三个动作

右键菜单（编辑器内）或 `Tools` 菜单里可以找到：

| 菜单项 | 编译选项（默认） | 执行器 |
| --- | --- | --- |
| `OI: Run Current File (Release)` | `-O2 -DNDEBUG` | Run |
| `OI: Run Current File (Debug)` | `-O0 -g` | Run（不开调试器，用于对比耗时） |
| `OI: Debug Current File` | `-O0 -g` | Debug |

调试会话只报告 `Wall time` 和退出码：此时 CLion 交给插件的进程是调试器（GDB/LLDB）本身，不是被调试程序，对它取 CPU/内存没有意义。

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
| 系统 | Windows（统计依赖 kernel32 / psapi）；Linux / macOS 上插件仍可运行，但只输出 `Wall time` |
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

- 极短命的进程（存活 < 5 ms）可能在插件抓到句柄前就退出，此时只报 `Wall time`。
- `CPU time` 受 Windows 计时粒度影响，量化步长约 15.6 ms，因此小程序的数值会呈阶梯状。
- 调试模式下不做 CPU / 内存统计（原因见上）。
- 插件创建的是临时（temporary）运行配置，会随 CLion 的常规清理策略消失；重新点一次动作即可再建。

## 许可

[MIT](LICENSE)
