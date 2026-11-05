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
	defOutboundTag := box.Outbound().Default().Tag()
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
