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
- **试题集对拍（OI Cases）**：右侧 Tool Window 里给当前文件挂一组案例，输入 / 期望输出 / 实际输出三栏并排，一键全跑，按行高亮差异——交互仿小熊猫 C++ 的「试题集」与 CPH 的案例面板。
- **零依赖、零下载**：不引入任何第三方库，进程统计用 JDK 自带的 JNA 直调 Win32 API。

## 四个动作

右键菜单（编辑器内）或 `Tools` 菜单里可以找到：

| 菜单项 | 编译选项（默认） | 执行器 |
| --- | --- | --- |
| `OI: Run Current File (Release)` | `-O2 -DNDEBUG -std=gnu++17` | Run |
| `OI: Run Current File (Debug)` | `-O0 -g` | Run（不开调试器，用于对比耗时） |
| `OI: Debug Current File` | `-O0 -g` | Debug |
| `OI: Test Cases` | 判题选项（默认 `-O2 -DNDEBUG -std=gnu++17`） | 插件自己编译 + 起进程 |

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
| `[no exit time]` | 抓到了句柄但读不到退出时间（极少见），用创建时间到当前系统时间估算，仍然不含编译 |

不设断点、直接一路跑完的调试会话，也能看到 `[debug]` 那一行；注意它跑的是 `-O0 -g` 的构建，
和 Release 的数字不能直接对比。

编译选项在 **Settings → Tools → OI Runner** 里修改，改完下次运行即生效；插件只会同步它自己创建的那两个配置，不会碰你手写的配置。

## 试题集（OI Cases）

右侧边栏的 **OI Cases** 面板（或 `Tools → OI: Test Cases`）。面板永远跟随当前打开的 `.c/.cpp` 文件：换文件就换一整套案例，案例挂在**文件路径**上。

每页一个案例，页签上是彩色圆点：

| 圆点 | 含义 |
| --- | --- |
| 灰 | 还没跑过 |
| 绿 | 通过（输出比对相同） |
| 红 | 未通过（WA / TLE / MLE / RE / CE） |

页内上下两个可拖拽分隔条，把空间切成三栏：

- **输入**：喂给 stdin 的内容
- **期望输出**：正确答案
- **实际输出**：只读，跑完后填进来；与期望不一致的行会整行高亮（红底），行号栏对得上

工具条从左到右：`＋ 案例`、`－ 案例`、`▶ 全部运行`、`▶ 运行当前`、`导入 .in/.out`，右边是**时限**（毫秒，默认 1000）与**内存上限**（MB，默认 256），状态行显示 `x/y 通过 · 编译 z s`。

### 判题怎么做的

- **编译一次，只计运行时间**：整组案例共用一次编译（`g++` + Settings 里的判题选项），产物落在 `%TEMP%\oi-runner\<文件名>-<hash>\`，编译耗时单独显示，**不进入**任何案例的 Wall time。
- **每个案例**：起一个子进程，stdin 接输入、stdout 收输出，用内核记录的创建→退出时间算 Wall time，同时取 CPU 时间与峰值内存。
- **判定顺序**：编译失败 → CE；超过内存上限 → MLE；退出码非 0（Windows 上崩溃常见 `0xC0000005`）→ RE；超过时限 → TLE（超时后 kill 整个进程树）；否则逐行按空白分词比较，相同 → AC，否则 WA。
- **比对规则**：忽略行尾空白与多余空行（`split()` 后逐 token 比），和大多数 OJ 的默认规则一致。

### 存储

案例存在项目级的 `<项目>/.idea/oi-runner.xml`，跟项目一起走，可以提交、可以拷贝给同学；不写 JSON，也不碰你手写的运行配置。结构大致是（省略了 XML 序列化器自动补的包装节点）：
<component name="OiRunnerCases">
  <option name="timeLimitMillis" value="1000" />
  <option name="memoryLimitMb" value="256" />
  <option name="sets">
    <entry key="$PROJECT_DIR$/a.cpp">
      <value>
        <option name="cases">
          <list>
            <OiCase name="样例 1" input="1 2" expected="3" actual="3" />
          </list>
        </option>
      </value>
    </entry>
  </option>
</component>
```

### 和 CPH 的差别

一样的地方：案例页签 + 三栏 + 圆点 + 行级 diff + 一键全跑。
不一样的地方：**没有**开 `Competitive Companion` 监听端口、没有在线提交、没有抓题面、没有题库——只做本地对拍；另外判题用的编译选项和运行用的 release/debug 选项是分开的，改判题选项不会影响你平时点运行。

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
export CLION_HOME="D:/CLion"
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
- 试题集的编译器默认取 CLion 自带工具链（`<CLion 目录>/bin/mingw/bin/g++.exe`），找不到就退回 `PATH` 里的 `g++`；用的是其他工具链（MSVC 等）时请在 **Settings → Tools → OI Runner → Test-case compiler** 里写绝对路径。
- 判题输出是纯文本比较，不做浮点误差容忍（需要请自己 `printf` 控制位数）；交互题、Special Judge 概不支持。
- 案例面板的输入/期望框是纯文本编辑器，不含代码补全；大案例（几万个 token）编辑会有轻微延迟。

## 许可

[MIT](LICENSE)
