# 商品售后查询台

Windows 桌面工具。通过 ERP 销售主题分析查询商品的实发、退货及补发情况，可查看售后率和导出订单明细。

## 项目结构

```text
.
├── 启动商品售后查询台.cmd          # 当前版本的启动入口
├── 商品售后查询台-Windows.zip      # Windows 分发包
├── report-desk/                   # 当前版本
│   ├── Desk.java                  # Swing 界面及命令行入口
│   ├── Metrics.java、Xlsx.java     # Excel 解析、统计及明细导出
│   ├── Store.java、Bulk.java、GroupReports.java  # 报表存储、批量采集和分组查询
│   ├── BatchBrowser.java、browser.cjs           # ERP 浏览器采集
│   ├── Grain.java、CaseCheck.java                 # 报表粒度与案例校验
│   ├── browser-settings.json      # ERP 页面定位配置
│   ├── remark-aliases.json        # 补发备注简称配置
│   ├── driver/                    # Node.js、配套 DLL 和 Playwright Core
│   ├── tests/                     # 浏览器与报表校验测试
│   ├── build.ps1、package.ps1      # 构建与打包
│   ├── report-desk-desktop.jar    # 当前版本的构建产物
│   └── 使用说明.md               # 操作、部署与维护说明
├── lib/                           # Java 依赖，目前为 Gson
└── runtime/                       # 随包提供的 Windows Java 运行时
```

## MVP 最小分发结构

以手动查询为范围，支持按商品、日期和仓库查询，查看订单并导出明细。按以下相对路径打包，完整复制 `runtime/` 和 `driver/`，包括其中的 DLL、依赖及许可文件。

```text
.
├── 启动商品售后查询台.cmd
├── lib/
│   └── gson-2.13.2.jar
├── runtime/                       # 整个目录
└── report-desk/
    ├── report-desk-desktop.jar
    ├── browser.cjs
    ├── browser-settings.json
    ├── remark-aliases.json
    └── driver/                    # 整个目录
```

接收方在 Windows 上完整解压，保留目录层级，双击 `启动商品售后查询台.cmd`。执行 ERP 采集需要本机安装 Chrome 或 Edge，并能访问 ERP 的「销售主题分析」报表。首次使用时，在工具打开的专用浏览器窗口登录自己的 ERP 账号。Java 和 Node.js 随包提供。

可额外附上 `report-desk/使用说明.md`。启用每日自动刷新需加入 `report-desk/daily.cmd` 和 `report-desk/启用每日刷新.ps1`。`report-desk/验证商品归属.ps1` 用于验证全商品采集模式。

分发时以清单选取文件。浏览器登录资料、请求抓包、真实订单导出、报表缓存和离线案例数据均留在本机。新用户的报表和浏览器资料分别保存在 `%LOCALAPPDATA%\AfterSalesDesk\data` 和 `%LOCALAPPDATA%\AfterSalesDesk\Browser`。

## 构建与打包

仓库根目录已有 `商品售后查询台-Windows.zip`，可直接分享。它含上面的 MVP 文件，以及使用说明、每日刷新和商品归属验证脚本。

源码有更新时，从仓库根目录在 PowerShell 中执行以下命令。构建需设置 `JAVA_HOME` 指向 JDK 17，也可向 `build.ps1` 传入 `-Jdk`。

```powershell
& .\report-desk\build.ps1
& .\report-desk\package.ps1
```

`package.ps1` 默认重新生成根目录的 Windows 分发 ZIP。只分发手动查询功能时，按 MVP 清单取文件压缩，确保解压后的启动脚本与 `lib/`、`runtime/`、`report-desk/` 同级。
