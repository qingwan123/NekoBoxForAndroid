# NekoBox for Android (sing-box 1.14 fork)

个人维护的 [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) 分支：
内核升级到 **sing-box 1.14**，并修掉了一批上游遗留的统计 / UI 问题。

原作者已停止维护，本仓库用于自用构建与本机验证。

## 目录结构

```
.
├── NekoBoxForAndroid/   Android 主体（app / libcore / buildScript）
├── sing-box/            内核 fork（libcore 通过 go.mod replace 引用）
├── libneko/             协议扩展库
└── docs/patches/        相对上游的全部补丁（可重放）
```

`NekoBoxForAndroid/libcore/go.mod` 使用 `replace ../../sing-box` 与 `../../libneko`，
所以三者必须保持**同级目录**，不要单独移动。

## 上游基线

| 组件 | 上游 | 基线提交 |
|---|---|---|
| NekoBoxForAndroid | MatsuriDayo/NekoBoxForAndroid | `5768494` (1.4.2) |
| sing-box | MatsuriDayo/sing-box (`1.14.x`) | `0bd0381` (1.14.0-neko-1) |
| libneko | MatsuriDayo/libneko | `1c47a3a` |

所有在基线上的改动都以 patch 形式保存在 `docs/patches/`，
方便上游更新后重放（`git am` 或 `git apply`）。

## 本分支包含的改动

内核
- 迁移到 sing-box 1.14 API（`adapter.PlatformInterface`、`box.Context` 六参数、证书注册表）
- DNS 新格式（`type`/`server`/`server_path`、独立 `fakeip` server、`action: "reject"`）
- route 新体系（`action: "sniff"` / `"resolve"`、`route.final`、`default_domain_resolver`）
- tun `address` 字段（1.12 移除 `inet4_address`/`inet6_address`）
- `RoutedFlow` 实现，恢复 1.13+ flow 路径的流量统计
- 流量归属到具体节点 tag（而不是 selector 组），修复速度显示
- naive 改为内核内置，新增 Snell 协议

App
- 内置五条路由规则默认启用（屏蔽 QUIC / 广告 / Play 商店 / 中国域名 / 中国 IP）
- `speedInterval` 默认值修复（新装不再需要重启才有速度）
- 设置页初始化期间不再误弹「重启服务以应用」
- 启动按钮跟随速度栏滑动的布局修复

## 构建

依赖：JDK 21、Go 1.25、Android SDK（platform 35、build-tools 35、**NDK 29**）、
[gomobile-matsuri](https://github.com/MatsuriDayo/gomobile)（分支 `master2`）。

> NDK 必须 29.x：naive 引入的 cronet-go 预编译库使用较新的 LLVM relocation，
> NDK 27 的 lld 会报 `unknown relocation (315)`。

本地一键构建（Windows / git-bash，路径按自己环境改）：

```bash
bash NekoBoxForAndroid/build-local.sh all
```

产物：`NekoBoxForAndroid/app/build/outputs/apk/oss/debug/`

也可以分步：

```bash
# 1. 重新绑定内核
cd NekoBoxForAndroid/libcore && gomobile-matsuri bind -androidapi 21 -target=android/arm64 \
  -cache "$(pwd)/.build" -trimpath -ldflags='-s -w' \
  -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_naive_outbound' \
  -o ../app/libs/libcore.aar .

# 2. 打包
cd .. && ./gradlew assembleOssDebug
```

**构建目录必须使用纯 ASCII 路径**。AGP 在中文路径下会在 SDK 加载和 AIDL 阶段
直接崩（`IOException: 文件名、目录名或卷标语法不正确` / `MalformedInputException`）。

CI 见 `.github/workflows/build.yml`（推 tag 或手动触发，产物为 debug APK）。

## 签名

`NekoBoxForAndroid/release.keystore` 是一份**固定的开发签名**（alias `androiddebugkey`），
本地和 CI 使用同一份，因此 CI 产物可以直接覆盖安装本地构建的版本。

密码（`KEYSTORE_PASS` / `ALIAS_PASS` = `android`，`ALIAS_NAME` = `androiddebugkey`）
写在 `local.properties`（本地）和 workflow 的环境变量里（CI）。

> 注意：这份密钥是公开的，只能保证「更新可以覆盖安装」，**不能防冒充**。
> 如果你要正式对外发布并防止他人签出同名包，需要换成自己私有的密钥：
>
> ```bash
> keytool -genkeypair -v -keystore my-release.jks -alias mykey \
>   -keyalg RSA -keysize 2048 -validity 10000
> ```
>
> 换签名后老版本无法覆盖安装，必须卸载重装；同时要把 CI 的密码挪到
> GitHub Secrets 里，不要把私有密钥提交进仓库。


## 更新内核

上游 sing-box 更新时：

```bash
git clone --depth=1 -b 1.14.x https://github.com/MatsuriDayo/sing-box /tmp/sing-box
rsync -a --delete --exclude=.git /tmp/sing-box/ sing-box/
cd sing-box && git apply ../docs/patches/sing-box/*.patch   # 重放本分支补丁
```

App 侧同理，并把 `NekoBoxForAndroid/buildScript/lib/core/get_source_env.sh`
里的 `COMMIT_SING_BOX` 一并更新（用 buildScript 自动拉源码时才会用到）。

## License

GPL-3.0，见 `NekoBoxForAndroid/LICENSE`。本项目是 NekoBox for Android 的衍生作品，
保留原作者版权声明（`AUTHORS`）。分发二进制时必须同时提供对应源码。
