package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"libcore/procfs"
	"log"
	"net/netip"
	"strings"
	"syscall"
	"time"

	"github.com/matsuridayo/libneko/neko_log"
	"github.com/sagernet/sing-box/adapter"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
)

var boxPlatformInterfaceInstance adapter.PlatformInterface = &boxPlatformInterfaceWrapper{}

type boxPlatformInterfaceWrapper struct{}

func (w *boxPlatformInterfaceWrapper) Initialize(networkManager adapter.NetworkManager) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState(ctx context.Context) adapter.WIFIState {
	state := strings.Split(intfBox.WIFIState(), ",")
	if len(state) < 2 {
		return adapter.WIFIState{}
	}
	return adapter.WIFIState{
		SSID:  state[0],
		BSSID: state[1],
	}
}

func (w *boxPlatformInterfaceWrapper) RequestPermissionForWIFIState() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformWIFIMonitor() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) AutoDetectInterfaceControl(fd int) error {
	// UI 进程里没有 VpnService，只能把 fd 交给 :bg 进程里的 protect 服务去 protect。
	// 这里必须重试 + 等足够长：bg 进程正在跑流量时，100ms 的 ack 超时会让 fd 悄悄
	// 变成「未 protect」，测速实例的 socket 就落进自己的 tun —— 表现为「连着代理时
	// 所有节点测速超时、关掉代理就正常」。
	if !isBgProcess {
		var lastErr error
		for attempt := 0; attempt < 3; attempt++ {
			lastErr = sendFdToProtect(fd, "protect_path")
			if lastErr == nil {
				return nil
			}
			// protect 服务不存在 / 没在监听 = VPN 没跑，此时没有 tun，不需要 protect
			if errors.Is(lastErr, syscall.ENOENT) || errors.Is(lastErr, syscall.ECONNREFUSED) {
				return nil
			}
			if attempt < 2 {
				time.Sleep(25 * time.Millisecond)
			}
		}
		log.Println("protect via protect_path failed:", lastErr)
		return E.Cause(lastErr, "protect fd via protect_path")
	}
	// bg process call VPNService
	return intfBox.AutoDetectInterfaceControl(int32(fd))
}

func (w *boxPlatformInterfaceWrapper) UsePlatformInterface() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	if len(options.IncludeUID) > 0 || len(options.ExcludeUID) > 0 {
		return nil, E.New("android: unsupported uid options")
	}
	if len(options.IncludeAndroidUser) > 0 {
		return nil, E.New("android: unsupported android_user option")
	}
	a, _ := json.Marshal(options)
	b, _ := json.Marshal(platformOptions)
	tunFd, err := intfBox.OpenTun(string(a), string(b))
	if err != nil {
		return nil, fmt.Errorf("intfBox.OpenTun: %v", err)
	}
	// Do you want to close it?
	tunFd, err = syscall.Dup(tunFd)
	if err != nil {
		return nil, fmt.Errorf("syscall.Dup: %v", err)
	}
	//
	options.FileDescriptor = tunFd
	return tun.New(*options)
}

func (w *boxPlatformInterfaceWrapper) ProcessPlatformOptions(options option.TunPlatformOptions) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformDefaultInterfaceMonitor() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) CreateDefaultInterfaceMonitor(logger logger.Logger) tun.DefaultInterfaceMonitor {
	return &interfaceMonitorStub{}
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNetworkInterfaces() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, errors.New("android: NetworkInterfaces not implemented")
}

func (w *boxPlatformInterfaceWrapper) UnderNetworkExtension() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) NetworkExtensionIncludeAllNetworks() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) ClearDNSCache() {
}

func (w *boxPlatformInterfaceWrapper) UsePlatformConnectionOwnerFinder() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	var uid int32
	if useProcfs {
		sourceAddr, _ := netip.ParseAddr(request.SourceAddress)
		destAddr, _ := netip.ParseAddr(request.DestinationAddress)
		var network string
		switch request.IpProtocol {
		case int32(syscall.IPPROTO_TCP):
			network = "tcp"
		case int32(syscall.IPPROTO_UDP):
			network = "udp"
		default:
			return nil, E.New("unknown protocol: ", request.IpProtocol)
		}
		uid = procfs.ResolveSocketByProcSearch(network,
			netip.AddrPortFrom(sourceAddr, uint16(request.SourcePort)),
			netip.AddrPortFrom(destAddr, uint16(request.DestinationPort)))
		if uid == -1 {
			return nil, E.New("procfs: not found")
		}
	} else {
		var err error
		uid, err = intfBox.FindConnectionOwner(request.IpProtocol, request.SourceAddress, request.SourcePort, request.DestinationAddress, request.DestinationPort)
		if err != nil {
			return nil, err
		}
	}
	packageName, _ := intfBox.PackageNameByUid(uid)
	owner := &adapter.ConnectionOwner{
		UserId: uid,
	}
	if packageName != "" {
		owner.PackageNames = []string{packageName}
	}
	return owner, nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoRedirect() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CreateAutoRedirect(options adapter.AutoRedirectOptions) (adapter.AutoRedirectSession, error) {
	return nil, E.New("auto redirect not supported")
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNotification() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) SendNotification(notification *adapter.Notification) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) CancelNotification(identifier string, typeID int32) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) MyInterfaceAddress() []netip.Addr {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNeighborResolver() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) StartNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) CloseNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformShell() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CheckPlatformShell() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) OpenShellSession(user *adapter.PlatformUser, command string, environ []string, term string, rows int32, cols int32) (adapter.ShellSession, error) {
	return nil, E.New("android: shell session not supported")
}

func (w *boxPlatformInterfaceWrapper) LookupUser(username string) (*adapter.PlatformUser, error) {
	return nil, E.New("android: LookupUser not supported")
}

func (w *boxPlatformInterfaceWrapper) LookupSFTPServer() (string, error) {
	return "", E.New("android: LookupSFTPServer not supported")
}

func (w *boxPlatformInterfaceWrapper) ReadSystemSSHHostKey() ([]byte, error) {
	return nil, E.New("android: ReadSystemSSHHostKey not supported")
}

func (w *boxPlatformInterfaceWrapper) TailscaleHostname() string {
	return ""
}

func (w *boxPlatformInterfaceWrapper) UsePlatformBridge() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) CreateBridge(options adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, E.New("android: bridge not supported")
}

// io.Writer

var disableSingBoxLog = false

func (w *boxPlatformInterfaceWrapper) Write(p []byte) (n int, err error) {
	// use neko_log
	if !disableSingBoxLog {
		log.Print(string(p))
	}
	return len(p), nil
}

// 日志

type boxPlatformLogWriterWrapper struct {
}

var boxPlatformLogWriter sblog.PlatformWriter = &boxPlatformLogWriterWrapper{}

func (w *boxPlatformLogWriterWrapper) DisableColors() bool { return true }

func (w *boxPlatformLogWriterWrapper) WriteMessage(level uint8, message string) {
	if !strings.HasSuffix(message, "\n") {
		message += "\n"
	}
	neko_log.LogWriter.Write([]byte(message))
}
