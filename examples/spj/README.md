# SPJ 示例：从区间中任选一个整数

输入正整数 `n`；选手只需输出从 1 到 `n` 的任意一个整数。[demo.cpp](demo.cpp) 总是输出 2。每个 `.ans` 只记录一个合法答案，并不是唯一答案。

1. 在 CLion 中打开 [demo.cpp](demo.cpp)，将 [cases 文件夹](cases/) 拖入该源文件的 OI Cases 面板。应出现 3 组案例。
2. 用普通比较运行全部案例，按编号检查第一列判定。
3. 启用 SPJ，选择从 [checker.cpp](checker.cpp) 编译的可执行文件，再运行相同案例，检查第二列判定。只运行可信的 checker。

| 案例 | 普通比较 | SPJ | 原因（实际输出均为 2） |
|---|---|---|---|
| 1 | WA | AC | 范围 1 到 3，参考答案是 1，输出 2 也合法。 |
| 2 | WA | WA | 范围只有 1，输出 2 越界。 |
| 3 | AC | AC | 范围 1 到 2，参考答案和实际输出都是 2。 |

仓库不包含二进制，先用 C++17 编译器把它编译成可执行文件（Windows 上 MinGW 示例）：

```powershell
g++ -std=c++17 -O2 -static -s "<仓库路径>/examples/spj/checker.cpp" -o checker.exe
```

调用协议：`checker.exe <输入文件> <选手输出文件> <参考答案文件>`。此 checker 返回 0 表示 AC，1 表示 WA；文件打不开或参考数据不合法返回 3，表示判题器错误而非选手 WA。诊断写到标准错误。
