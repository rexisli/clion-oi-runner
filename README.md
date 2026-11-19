# OI Runner

给 [CLion](https://www.jetbrains.com/clion/) 用的轻量插件：**一键构建并运行**当前正在看的 C/C++ 单文件，进程结束后在控制台打印**运行耗时、CPU 时间、峰值内存**，并提供一个**试题集面板**用来跑用例、比对输出。为 OI（信息学奥赛）本地调试与卡常服务。

不接管编译，不接管调试：编译、链接、工具链、代码补全与动态检查全部还是 CLion 自己的流程，插件只在进程退出后补一行统计，另外多一个判题面板。

## 特性

- **跟随当前文件**：不用先去 Run 下拉框里挑配置，光标在哪个 `.cpp` 里就构建哪个。
- **退出后打印统计（Run 与 Debug 都测）**

```text
  -----------------------------
  Process finished with exit code 0
  Wall time: 1.0126 s | CPU time: 31.2500 ms | Peak memory: 4.41 MB
```

  - `Wall time`：进程实际存活时长（含被调度走的时间），取自内核记录的**创建时间到退出时间**
  - `CPU time`：用户态 + 内核态的 CPU 时间，来自 `GetProcessTimes`
  - `Peak memory`：峰值工作集，来自 `GetProcessMemoryInfo` 的 `PeakWorkingSetSize`

  三个数字都取自**被运行的程序本体**，控制台窗口晚弹出、程序 `freopen` 后不读标准输入，都不会污染计时。
- **Release / Debug 两套编译选项**：CLion 的单文件运行配置只有一个编译选项字段，Run 和 Debug 共用一套。本插件把它拆成两个独立配置（`<文件名> [release]` / `<文件名> [debug]`），选项在设置里改，互不影响。
- **试题集（OI Cases）**：右侧 Tool Window 里给当前文件挂一组案例，输入 / 期望输出 / 实际输出三栏并排，一键全跑，按行高亮差异。交互参考小熊猫 C++ 的「试题集」与 CPH 的案例面板。
- **零依赖、零下载**：不引入任何第三方库，进程统计用 JDK 自带的 JNA 直调 Win32 API；从源码构建也不需要 Gradle 与联网。

## 四个动作

右键菜单（编辑器内）或 `Tools` 菜单里可以找到：

| 菜单项 | 编译选项（默认） | 执行器 |
| --- | --- | --- |
| `OI: Run Current File (Release)` | `-O2 -DNDEBUG -std=gnu++17` | Run |
| `OI: Run Current File (Debug)` | `-O0 -g` | Run（不开调试器，用于对比耗时） |
| `OI: Debug Current File` | `-O0 -g` | Debug |
| `OI: Test Cases` | 判题选项（默认 `-O2 -DNDEBUG -std=gnu++17`） | 插件自己编译 + 起进程 |

### 调试模式下的计时

调试时 CLion 交给插件的事件对应的是**调试器会话**（启动调试后端 → 加载程序 → 调试 → 收尾），直接拿这个窗口当运行时长，会把调试器冷启动那 1~2 秒算进去。

所以插件会在会话开始时按**可执行文件名**去调试器的子进程树里找真正被调试的程序（IDE 自己进程的子进程也会找，必要时扫一遍全部进程），找到后用它的内核创建/退出时间计时，CPU 时间和峰值内存同样对它取。

控制台末尾会带一个标记，表示这个数字是怎么来的：

| 标记 | 含义 |
| --- | --- |
| 无标记 | 直接对程序本体取的内核时间，准确 |
| `[debug]` | 调试会话里定位到了被调试程序，数字同样是它本体的内核时间 |
| `[debug session]` | 没能在调试器树里定位到程序（例如程序太短、还没轮询到就退了），退化成整个调试会话的时长，只作粗略参考 |
| `[approximate]` | 进程句柄没抓到（极短命的程序），退化成 IDE 事件窗口的时长 |
| `[no exit time]` | 抓到了句柄但读不到退出时间（极少见），用创建时间到当前系统时间估算，仍然不含编译 |

不设断点、直接一路跑完的调试会话，也能看到 `[debug]` 那一行；注意它跑的是 `-O0 -g` 的构建，和 Release 的数字不能直接对比。

编译选项在 **Settings → Tools → OI Runner** 里修改，改完下次运行即生效；插件只会同步它自己创建的那两个配置，不会碰手写的配置。

## 试题集（OI Cases）

右侧边栏的 **OI Cases** 面板（或 `Tools → OI: Test Cases`）。面板跟随当前打开的 `.c/.cpp` 文件：换文件就换一整套案例，案例按**文件路径**归属。

每页一个案例，页签上是案例名，前面带判定（仿 CPH-NG）：

| 页签 | 含义 |
| --- | --- |
| 灰点 `C1` | 还没跑过 |
| 绿点 `AC` | 通过（输出比对相同） |
| 红点 `WA` | 答案错误 |

判定文字显示在页签图标里；TLE / MLE / RE / CE 同样直接标出来。

页内上下两个可拖拽分隔条，把空间切成三栏，每栏各自带滚动条：

- **输入**：喂给 stdin 的内容
- **期望输出**：正确答案
- **实际输出**：只读，跑完后填进来；与期望不一致的行整行标红。红色标记以**当前**期望输出为准：改期望会立刻重算，重新运行也会清掉上一轮的标记

工具条是六个单字母小按钮，悬停有说明：`+` 添加案例、`-` 删除当前案例、`C` 清空全部案例、`I` 导入案例（也可拖放）、`A` 运行全部案例、`T` 运行当前案例；右边是**时限**（毫秒，默认 1000）与**内存上限**（MB，默认 256）。

`C` 会先确认一次，删除不可撤销。状态行边跑边报，跑完整组后汇总，例如 `AC 3 · WA 1 · 共 4 个案例 · 编译 0.42 s`。

案例多时页签栏单行滚动，用箭头翻，不会折成多行。

### 导入案例

两种方式：点 `I` 选择文件/文件夹，或者**把文件夹 / .zip 直接拖进面板**（页签栏、按钮栏、底部状态栏都接得住，拖进来时状态栏会提示「松开鼠标导入案例…」）。

- **文件夹**：目录里的 `1.in / 1.out`、`2.in / 2.out`…… 按数字顺序配成案例（`.ans` 也认），子目录最多走 4 层，所以选一个装着 `1/`、`2/` 子文件夹的父目录也能配对。
- **.zip**：包内 `X.in` 找同目录的 `X.out` / `X.ans`，子目录也认。文件名按 UTF-8 优先、读不出来再按 GBK 解（中文 Windows 压出来的包常是 GBK）。
- **.in 文件**：可多选，每个 `.in` 去同目录找同名 `.out`（或 `.ans`）配一个案例。

文件按 UTF-8 读，读不出来再按 GBK 读；单文件上限 64 MB，一次最多导入 200 个案例。案例名取文件名主干；导入完成后若有的案例输入为空，状态行会补一句「N 个输入为空」。

导入在后台任务里进行，IDE 底部有进度与「取消」。三栏每栏最多显示前 12 万个字符，超出部分在最后一个换行处截断、标题写成「输入 · 已截断」并转为只读——判题始终用完整数据，只有显示被截。

### 打开时机与索引

侧边栏在项目打开时是收起的，索引没建完前右侧图标是灰的、点不开；索引完成后自动恢复可用。若面板在打开状态下遇到重新索引（例如 Invalidate Caches），页签区会显示「正在建立索引，建好后自动载入案例」，索引一完成自动补建。

### 判题怎么做的

- **编译一次，只计运行时间**：整组案例共用一次编译（`g++` + Settings 里的判题选项），编译耗时单独显示，**不进入**任何案例的 Wall time。
- **每个案例**：起一个子进程，stdin 接输入、stdout 收输出，用内核记录的创建→退出时间算 Wall time，同时取 CPU 时间与峰值内存。
- **判定顺序**：编译失败 → CE；超过内存上限 → MLE；退出码非 0（Windows 上崩溃常见 `0xC0000005`）→ RE；超过时限 → TLE（超时后 kill 整个进程树）；否则逐行按空白分词比较，相同 → AC，否则 WA。
- **比对规则**：忽略行尾空白与多余空行（`split()` 后逐 token 比），和多数 OJ 的默认规则一致。

### 存储

案例存在项目级的 `<项目>/.idea/oi-runner.xml`，跟项目一起走，可以提交、可以拷贝给同学；不写 JSON，也不碰手写的运行配置。结构大致如下（省略了 XML 序列化器自动补的包装节点）：

```xml
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

一样的地方：案例页签 + 三栏 + 判定标记 + 行级 diff + 一键全跑 + 文件夹/压缩包批量导入（含拖放）+ 汇总统计。

不一样的地方：**没有**开 Competitive Companion 监听端口、没有在线提交、没有抓题面、没有题库、没有对拍（Stress Test）——只做本地案例判定。判题用的编译选项和运行用的 release/debug 选项是分开的，改判题选项不影响平时点运行。

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

直接用 CLion 自带的 `lib/*.jar` 当 SDK，`javac` 编完打 jar 即可。

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
- `CPU time` 受 Windows 计时粒度影响，量化步长约 15.6 ms，小程序的数值会呈阶梯状。
- 调试会话里定位程序靠「可执行文件名匹配 + 进程启动时间不早于会话开始」，若同时有另一个同名程序在跑，理论上可能认错；定位成功后插件会把 pid 写进 `idea.log`（`OI Runner: timing pid ...`）以便核对。
- 插件创建的是临时（temporary）运行配置，会随 CLion 的常规清理策略消失；重新点一次动作即可再建。
- 试题集的编译器默认取 CLion 自带工具链（`<CLion 目录>/bin/mingw/bin/g++.exe`），找不到就退回 `PATH` 里的 `g++`；用其他工具链（MSVC 等）时请在 **Settings → Tools → OI Runner → Test-case compiler** 里写绝对路径。
- 拖放只识别文件列表类拖拽数据（资源管理器、桌面、压缩软件都可以）；从文本编辑器里拖一段文字进来不会当成案例。
- 刚编译出的程序第一次启动可能被杀毒软件实时扫描拖慢 0.15~0.35 s，所以第 1 个案例若判成 TLE 会自动重测一次，以第二次为准。
- 判题是纯文本比较，不做浮点误差容忍（需要时自己用 `printf` 控制位数）；交互题、Special Judge 不支持。
- 案例三栏是纯文本显示，不含代码补全与行号；超过 12 万字符的案例只显示前 12 万字符（判题仍用全量）。

## 许可

[MIT](LICENSE)
