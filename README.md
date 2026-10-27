# 山西扣点点麻将

Windows x64 桌面麻将程序，当前版本 **2.2.2**。使用 Java 21 / Swing 开发，无 Maven 或 Gradle 依赖。

支持一台电脑开房、四位朋友入座并准备后对局，也保留单机练习。不同国家的玩家可通过 Tailscale 连接房主电脑。

- 136 张标准麻将牌：万、筒、条各 1～9，以及东南西北中发白；没有赤五、花牌等额外牌种。
- 山西扣点点规则，支持亲友房设置及金币场规则的模拟积分。
- 风嘴子成牌仅限自摸，不能点炮或抢杠胡；普通成牌接炮按原规则处理。
- 局末四家亮牌，并可点击“查看全场牌”查看手牌、弃牌及副露；下一局恢复私牌隔离。
- 房间准备、逐笔结算、断线暂停、自动重连与自动回原座位，无需手输恢复码。
- 报听后不能碰或换手牌；平时只打刚摸到的牌，保持听口不变时可以杠，按普通杠计分。

完整使用说明在 [QingzhuMahjongSource/README.md](QingzhuMahjongSource/README.md)。

## 下载运行

**[下载 Windows x64 程序 ZIP（2.2.2，约 33 MB）](https://github.com/diganyousai/majiang/releases/download/v2.2.2/QingzhuKouDianDianOnline-2.2.2-Windows-x64.zip)**

完整解压后，进入 `QingzhuKouDianDianOnline` 文件夹，运行 `QingzhuKouDianDianOnline.exe` 或 `play.cmd`，无需安装 Java。保留同目录的 `app`、`runtime` 文件夹。

[版本发布页与启动说明](https://github.com/diganyousai/majiang/releases/tag/v2.2.2) · [SHA-256 校验文件](https://github.com/diganyousai/majiang/releases/download/v2.2.2/QingzhuKouDianDianOnline-2.2.2-Windows-x64.zip.sha256)

GitHub 的 **Code → Download ZIP** 以及发布页自动生成的 **Source code** 下载项均为源码；朋友直接运行时请选择上面的 Windows 程序 ZIP。

## 构建与验证

在 Windows x64 安装 JDK 21+ 后，在仓库根目录执行：

```powershell
Set-Location .\QingzhuMahjongSource
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -JdkHome 'C:\path\to\jdk-21'
```

构建会校验牌面素材、编译并执行规则和四客户端 TCP 测试，随后生成 JAR、精简 Java 运行时、Windows EXE 及 ZIP。产物位于 `QingzhuMahjongSource/dist/`。构建使用已包含的素材，不需要联网下载依赖。

只运行编译和测试时，增加 `-TestOnly`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -JdkHome 'C:\path\to\jdk-21' -TestOnly
```

分发时发送完整 ZIP；玩家无需安装 Java，解压后运行 `QingzhuKouDianDianOnline.exe` 或 `play.cmd`。

## 开发资料

- [四人联机、Tailscale 与测试方法](QingzhuMahjongSource/MULTIPLAYER_TEST.md)
- [规则实现与待确认内容](QingzhuMahjongSource/RULES_AUDIT.md)
- [联机模块与协议边界](QingzhuMahjongSource/ONLINE_DESIGN.md)
- [2.2.2 验证记录](QingzhuMahjongSource/RELEASE_VALIDATION.md)
- [素材来源与许可](QingzhuMahjongSource/ARTWORK.md)
- [第三方许可证说明](QingzhuMahjongSource/THIRD_PARTY_NOTICES.md)
- [开发规划及进度](work.md)

主机将牌局保存在内存中，主机关闭后无法恢复旧牌局。亲友房牌型数值和跟庄判定按现有说明留白。程序仅模拟积分。

仓库跟踪源码、测试、素材及文档；本地运行时、发布包、构建缓存和机器审计记录由 `.gitignore` 排除。
