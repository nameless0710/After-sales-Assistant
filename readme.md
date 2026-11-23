# 商品售后查询台

Windows 桌面工具。通过 ERP 销售主题分析查询商品的实发、退货及补发情况，可查看售后率和导出订单明细。

## 项目结构

```text
.
├── 启动商品售后查询台.cmd          # 当前版本的启动入口
├── 更新工具.cmd、update.ps1        # 从 Gitee 安装和同步运行文件
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
└── runtime/                       # 仓库提供的 Windows Java 运行时
```

## 安装与更新

首次使用时，把 `更新工具.cmd` 和 `update.ps1` 放到同一个可写的 Windows 文件夹，双击 `更新工具.cmd`。更新器会通过 HTTPS 读取 `master` 分支，下载启动脚本、应用 JAR、Java、Gson 和浏览器驱动。用户无需安装 Git、Java 或 Node.js。 [Gitee 仓库](https://gitee.com/an_602/After-sales-Assistant)

安装完成后双击 `启动商品售后查询台.cmd`。每次启动都会检查 `master` 的最新提交，按文件内容比对、下载并校验发生变化的文件，然后启动查询台。网络异常时会提示并尝试打开已安装版本。执行 ERP 采集需要本机安装 Chrome 或 Edge，并能访问 ERP 的「销售主题分析」报表。首次采集时，在工具打开的专用浏览器窗口登录自己的 ERP 账号。

同步会替换程序附带的 `browser-settings.json` 和 `remark-aliases.json`。如自行修改过这两个文件，更新前请备份。报表数据、ERP 登录资料和 `AFTERSALES_STORE` 指定的共享目录继续沿用。

### 中国大陆网络

默认直接访问 Gitee。如本机网络需要代理，可配置 `AFTERSALES_UPDATE_PROXY`，例如 `http://127.0.0.1:7890`。也可把维护者提供的可信 HTTPS 转发前缀写入安装根目录的 `update-source.txt`。转发服务须接受附在前缀后的完整原始 URL，并支持 `gitee.com/api/v5/`。从旧更新源迁移时，请替换安装目录里的 `update.ps1`。如果曾设置只支持旧更新源的 `update-source.txt`，请移除该文件或改用支持 Gitee 的前缀。

## 构建与发布

Java 源码变更后，从仓库根目录运行构建脚本，再把新的 `report-desk/report-desk-desktop.jar` 与相关变更一起提交并推送到 Gitee 的 `master`。构建需设置 `JAVA_HOME` 指向 JDK 17，也可向 `build.ps1` 传入 `-Jdk`。用户同步的是该分支上已提交的运行文件。

```powershell
& .\report-desk\build.ps1
```
