# 故障诊断

## 推荐排查顺序

先判断故障层级，再修改参数：服务进程是否存活 → UHD Python 绑定是否安装 → UHD 能否发现设备 → 主机与 USRP 网络是否互通 → 设备是否已被其他进程占用 → 收发参数是否一致 → 数据流是否发生 overflow、underflow 或 timeout。一次只改一个变量，并保存修改前后的状态与日志。

## UHD 或设备不可用

- `UHD not installed`：当前 Python 环境无法导入 UHD，先确认 UHD 与 Python 绑定安装在启动服务所用的解释器中。
- 找不到 USRP：核对设备 IP、主机网卡 IP、子网掩码、网线、交换机和供电，再使用 UHD 设备发现/探测命令确认设备与 FPGA 镜像。
- 连接被占用：停止旧的 Agent_SDR、GNU Radio 或其他 UHD 进程。项目设计为同一时刻只运行一个独占硬件任务。
- 服务在线但硬件离线：分别检查 `/api/health` 和 `/api/hardware_status`，不要把 FastAPI 在线误认为 USRP 已连接。

## RX overflow（O）

RX overflow 表示设备持续产生样本，但主机应用没有足够快地消费。网络型设备还可能因为内核套接字缓冲区满、UDP 包丢失或序号不连续而触发。排查顺序：降低采样率和通道数；缩短单次处理链；确认接收循环持续调用 `recv()`；检查 CPU 占用和电源管理；检查网卡丢包、PCIe 带宽、网线、交换机与 MTU。发生 overflow 后数据可能存在缺口，不能把该段样本当作连续测量。

## TX underflow（U）

TX underflow 表示 USRP 消耗样本的速度快于主机供数。优先预生成发送缓冲区，避免在发送热路径中执行耗时编码或日志；降低采样率；检查发送线程是否被阻塞；检查 CPU 调度和主机到设备链路。仅提高发射增益不会解决 underflow。

## Timeout、空 IQ 与数据包错误

Timeout 表示规定时间内没有收到数据包。检查流命令是否已经下发、天线/通道选择是否正确、设备是否仍连接以及超时值是否合理。`BAD_PACKET` 表示包无法解析；`ALIGNMENT` 常见于多通道时间对齐失败；`LATE_COMMAND` 表示定时命令设定在过去。读取 RX metadata 后必须区分这些错误，不能统一解释成“没有信号”。

## 能看到频谱但解调乱码

依次核对调制方式、中心频率、采样率、符号率、2-FSK 频偏、同步字和文本编码。再检查载波频偏、符号定时、SNR、增益削顶和包边界。若接收功率很强但星座散乱，先降低增益并检查直连衰减；若星座整体旋转，优先处理载波频偏。

## 官方资料

- [UHD Device Streaming](https://files.ettus.com/manual/page_stream.html)
- [UHD RX Streamer Error Handling](https://files.ettus.com/manual/classuhd_1_1rx__streamer.html)
- [USRP X3x0 Performance Troubleshooting](https://files.ettus.com/manual_archive/release_003_007_000/manual/html/usrp_x3x0_config.html)
