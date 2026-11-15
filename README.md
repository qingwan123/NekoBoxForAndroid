# NekoBox for Android · sing-box 1.15

基于 [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) 的自维护分支：
内核跟进 **SagerNet 上游 sing-box 1.15**，并持续修复原作者停更后遗留的测速、流量统计、UI 交互
和链接兼容问题。

原作者已停止维护，本仓库用于自用构建与继续修 bug。

[**下载 Release**](https://github.com/qingwan123/NekoBoxForAndroid/releases) · 仅 arm64-v8a ·
与旧版同一把签名密钥，可直接覆盖安装

## 相对上游的改动

### 内核

- **sing-box 升级到 1.15.0-alpha.13**（原 fork 停在 1.14），移植 fork 独有的 `nekoutils` / `boxapi`
- **启用 sing-tun 自研 TCP/IP 栈**：1.15 起不再写 `stack` 字段，设置里的「tun 实现」已隐藏
- **流量统计记到节点 tag**：记在 selector 组 tag 上会让上下行速度恒为 0
- **VLESS 后量子加密** `mlkem768x25519plus`（Xray ≥ v25.8.31），含 Vision 的 TLS 桥接
- 内置 geoip / geosite 资源随包分发

### 功能与修复

- **节点测速**：每个节点独立实例并发测速，逐条回显；已连接代理时改由后台进程执行，
  socket 可正常绕过 VPN 隧道，测速实例不再写 cache 文件
- **底栏**：保持原来的三行外观，交互补齐——连接后静默测一次握手延迟，点击底栏 / 切换节点 /
  回到前台都会重测，上滑收起、下滑弹出，未连接或离开节点页不显示
- **链接导入**：支持 Shadowrocket 风格链接（base64 编码的 `uuid@host:port` 配合
  `tls` / `peer` / `xtls` / `fingerprint` 参数），REALITY 公钥、short id、Vision flow、
  uTLS 指纹正确写入节点；粘贴、二维码、订阅都走这条解析路径
- **手动填写**：补齐 mux（模式、max_connections / min_streams、brutal 上下行带宽）
  与 ECH query server name
- **Snell** 协议补全 UI 并在 manifest 注册（原版点「添加 Snell」即闪退）
- 内置五条路由规则（屏蔽 QUIC / 广告 / Play 商店 / 中国域名 / 中国 IP）默认启用
- 默认远程 DNS 改为 `https://cloudflare-dns.com/dns-query`
- 修复「打开页面就提示重载代理服务」的一批误报（下拉项绑定、ViewHolder 复用、初始化创建规则等）

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

所有在基线上的改动都以 patch 形式保存在 `docs/patches/`，方便上游更新后重放。

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

打 `v*` tag 后由 GitHub Actions 自动绑定内核、构建 arm64-v8a release 并发布，
Release 正文取自 `docs/releases/<tag>.md`。历史版本见
[Releases](https://github.com/qingwan123/NekoBoxForAndroid/releases)。

## 许可

继承上游 GPL-3.0，保留 `LICENSE` / `AUTHORS`。移植的加密层代码来自
[juerson/sing-box-lx](https://github.com/juerson/sing-box-lx)（GPL-3.0）。
