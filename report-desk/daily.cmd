@echo off
setlocal
chcp 65001 > nul
title After-sales Desk - Daily Refresh
cd /d "%~dp0"
if not exist "%LOCALAPPDATA%\AfterSalesDesk" mkdir "%LOCALAPPDATA%\AfterSalesDesk"
if /i "%~1"=="--scheduled" (
    echo [商品售后查询台] Windows 计划任务 AfterSalesDesk-Daily 已启动。
) else (
    echo [商品售后查询台] 正在执行每日刷新。此脚本可由 Windows 计划任务或手动启动。
)
echo 正在按发货日期分段更新数据，可能生成多份 ERP 报表，请等待运行结束。
echo 可在查询台的「数据 - 每日自动刷新」开启或关闭后续运行。
echo 日志路径 "%LOCALAPPDATA%\AfterSalesDesk\daily.log"
>> "%LOCALAPPDATA%\AfterSalesDesk\daily.log" echo [%date% %time%] Daily refresh started.
"..\runtime\bin\java.exe" -Dfile.encoding=UTF-8 -cp "report-desk-desktop.jar;..\lib\*" Desk --daily >> "%LOCALAPPDATA%\AfterSalesDesk\daily.log" 2>&1
set "DAILY_EXIT=%errorlevel%"
>> "%LOCALAPPDATA%\AfterSalesDesk\daily.log" echo [%date% %time%] Daily refresh exit code %DAILY_EXIT%.
if "%DAILY_EXIT%"=="0" (echo 每日刷新已完成。) else (echo 每日刷新未完成，退出代码 %DAILY_EXIT%，详情请查看日志。)
exit /b %DAILY_EXIT%
