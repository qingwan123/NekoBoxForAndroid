# NekoBox for Android (sing-box 1.15 fork)

个人维护的 [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) 分支：
内核换成 **SagerNet 上游 sing-box 1.15**，修掉上游遗留的统计 / 测速 / UI 问题，
并补齐 VLESS 后量子加密。

原作者已停止维护，本仓库用于自用构建与本机验证。

## 目录结构

```
.
├── NekoBoxForAndroid/   Android 主体（app / libcore / buildScript）
├── sing-box/            内核（libcore 通过 go.mod replace 引用）
├── libneko/             协议扩展库
└── docs/patches/        相对上游的全部补丁（可重放）
```

`NekoBoxForAndroid/libcore/go.mod` 使用 `replace ../../sing-box` 与 `../../libneko`，
所以三者必须保持**同级目录**，不要单独移动。

## 上游基线

| 组件 | 上游 | 基线 |
|---|---|---|
| NekoBoxForAndroid | MatsuriDayo/NekoBoxForAndroid | `5768494` (1.4.2) |
| sing-box | **SagerNet/sing-box** | `v1.15.0-alpha.13` |
| libneko | MatsuriDayo/libneko | `1c47a3a` |

原作者的 fork 停在 sing-box 1.14，本仓库改跟 SagerNet 上游 1.15，并把 fork 独有的
`nekoutils` / `boxapi` 两个包（共 16K）移植过来，以后可以直接跟上游版本走。

所有在基线上的改动都以 patch 形式保存在 `docs/patches/`，方便上游更新后重放。

## 核心改动

### 内核

- **升级到 sing-box 1.15.0-alpha.13**：适配新的 `adapter.PlatformInterface`
  （`CreateAutoRedirect`）、`ConnectionOwner.PackageNames`、`DNSTransport.Start(stage, scope)`、
  geoip 的 `badoption.Prefixable`、wireguard 的 `LegacyListable` 等 API。
- **启用 sing-tun 自研 TCP/IP 栈**：生成 tun 入站时不再写 `stack` 字段
  （1.15 废弃、1.17 移除）。因此设置里的「tun 实现」已无作用，已隐藏。
- **VLESS 后量子加密**（`mlkem768x25519plus`，Xray ≥ v25.8.31）：移植自
  [juerson/sing-box-lx](https://github.com/juerson/sing-box-lx)，其中 `lx_vision.go`
  负责让 `flow=xtls-rprx-vision` 能跑在加密层之上（缺了它 Vision 会报
  `not a valid supported TLS connection`）。
- **流量统计记到节点 tag**：`route` 传给 tracker 的是 selector 组 tag，而 App 按节点 tag
  查速度，直接用组 tag 会让速度恒为 0 —— 改为用 `Now()` 解析到底层节点。
- 内置 geoip / geosite 资源随包分发（MaxMind DB + sing-box geosite 格式）。

### App

- **节点测速改为单实例并发**（对标 Clash 的 delay 接口）：整组节点只启动一次内核，
  按 outbound tag 并发拨号，每测完一个立刻回显。原实现每个节点都要启动一次内核，
  1.15 启动开销变重后慢得明显。
- **底栏手势驱动**：上滑收起、下滑弹出，不依赖列表滚动（节点少、内容不满一屏的分组也能用）；
  未连接代理时不响应滑动；离开节点页自动隐藏。
- **修复「打开页面就提示重载代理服务」**：下拉项绑定时的 `setSelection`、路由开关复用
  ViewHolder 时残留的监听、内置规则的初始化创建、全局设置监听的误报 —— 这些都不是用户
  改动，现在只改数据、不触发提示。
- 内置五条路由规则（屏蔽 QUIC / 广告 / Play 商店 / 中国域名 / 中国 IP）默认启用。
- 默认远程 DNS 改为 `https://cloudflare-dns.com/dns-query`。
- Snell 协议补全 UI 并在 manifest 注册（原版点「添加 Snell」即闪退）。
- 删除「像 SagerNet 一样显示底栏」开关：底栏离开节点页没有意义。

## 构建

依赖：JDK 21、Go 1.25、Android SDK（platform 35、build-tools 35、**NDK 29**）、
[gomobile-matsuri](https://github.com/MatsuriDayo/gomobile)（分支 `master2`）。

> NDK 必须 29.x：naive 引入的 cronet-go 预编译库使用较新的 LLVM relocation，
> NDK 27 的 lld 会报 `unknown relocation (315)`。

```bash
# 1) 绑定内核（产物 app/libs/libcore.aar）
cd NekoBoxForAndroid/libcore
gomobile-matsuri bind -androidapi 21 -target=android/arm64 \
  -cache "$PWD/.build" -trimpath -ldflags='-s -w' \
  -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_naive_outbound' \
  -o ../app/libs/libcore.aar .

# 2) 打 release APK
cd ..
gradle assembleOssRelease --no-daemon
# 产物: app/build/outputs/apk/oss/release/NekoBox-1.4.2-arm64-v8a.apk
```

构建目录必须是不含中文的纯 ASCII 路径，否则 AGP 在 SDK 加载和 AIDL 阶段会直接失败。

## 发布

打 `v*` tag 后由 GitHub Actions 自动构建 arm64-v8a release 并发布，
更新内容取自 `docs/releases/<tag>.md`。历史版本见
[Releases](https://github.com/qingwan123/NekoBoxForAndroid/releases)。

## 许可

继承上游 GPL-3.0，保留 `LICENSE` / `AUTHORS`。移植的加密层代码来自
[juerson/sing-box-lx](https://github.com/juerson/sing-box-lx)（GPL-3.0）。
