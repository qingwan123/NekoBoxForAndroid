package boxapi

import (
	"context"
	"net"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing/common/metadata"
)

func DialContext(ctx context.Context, box *box.Box, tracker adapter.ConnectionTracker, network, addr string) (net.Conn, error) {
	// 默认出口（outbounds[0]）在 NekoBox 生成的配置里是 direct，
	// 直接用它测速等于走直连 —— 连接代理后底栏测速失败就是这个原因。
	// 优先用 selector（tag 固定为 "proxy"），没有才退回默认出口。
	defOutboundTag := "proxy"
	if _, ok := box.Outbound().Outbound(defOutboundTag); !ok {
		defOutboundTag = box.Outbound().Default().Tag()
	}
	conn, err := dialer.NewDetour(box.Outbound(), defOutboundTag, true).DialContext(ctx, network, metadata.ParseSocksaddr(addr))
	if err != nil {
		return nil, err
	}
	if ss, ok := tracker.(*SbStatsService); ok {
		conn = ss.RoutedConnectionInternal("", defOutboundTag, "", conn, false)
	}
	return conn, nil
}
// DialContextWithTag 与 DialContext 相同，但走指定的 outbound（而不是默认出口），
// 用于「同一个内核实例里并发测试多个节点」。
func DialContextWithTag(ctx context.Context, box *box.Box, tracker adapter.ConnectionTracker, outboundTag, network, addr string) (net.Conn, error) {
	if outboundTag == "" {
		outboundTag = box.Outbound().Default().Tag()
	}
	conn, err := dialer.NewDetour(box.Outbound(), outboundTag, true).DialContext(ctx, network, metadata.ParseSocksaddr(addr))
	if err != nil {
		return nil, err
	}
	if ss, ok := tracker.(*SbStatsService); ok {
		conn = ss.RoutedConnectionInternal("", outboundTag, "", conn, false)
	}
	return conn, nil
}
