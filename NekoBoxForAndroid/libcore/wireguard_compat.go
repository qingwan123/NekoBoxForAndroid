package libcore

import (
	"context"
	"net/netip"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/wireguard"
	"github.com/sagernet/sing/common/json/badoption"
)

// sing-box 1.13 起移除了 wireguard outbound，只保留 wireguard endpoint（peers 数组格式）。
// NekoBox 的 GUI 仍按旧格式生成 type=wireguard 的 outbound，这里把旧字段转成
// endpoint options 后交给 wireguard.NewEndpoint，返回的对象同时实现了 adapter.Outbound，
// 因此可以直接当作 outbound 使用，老配置无需迁移。
type legacyWireGuardOutboundOptions struct {
	System        bool                             `json:"system,omitempty"`
	Server        string                           `json:"server,omitempty"`
	ServerPort    uint16                           `json:"server_port,omitempty"`
	LocalAddress  badoption.Listable[netip.Prefix] `json:"local_address,omitempty"`
	PrivateKey    string                           `json:"private_key,omitempty"`
	PeerPublicKey string                           `json:"peer_public_key,omitempty"`
	PreSharedKey  string                           `json:"pre_shared_key,omitempty"`
	MTU           uint32                           `json:"mtu,omitempty"`
	Reserved      []uint8                          `json:"reserved,omitempty"`
	Peers         []option.WireGuardPeer           `json:"peers,omitempty"`
	UDPTimeout    badoption.Duration               `json:"udp_timeout,omitempty"`
	Workers       int                              `json:"workers,omitempty"`
	option.DialerOptions
}

func registerLegacyWireGuardOutbound(registry *outbound.Registry) {
	outbound.Register[legacyWireGuardOutboundOptions](registry, "wireguard", func(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options legacyWireGuardOutboundOptions) (adapter.Outbound, error) {
		endpointOptions := option.WireGuardEndpointOptions{
			System:        options.System,
			MTU:           options.MTU,
			Address:       options.LocalAddress,
			PrivateKey:    options.PrivateKey,
			UDPTimeout:    options.UDPTimeout,
			Workers:       options.Workers,
			DialerOptions: options.DialerOptions,
		}
		allTraffic := []netip.Prefix{
			netip.PrefixFrom(netip.IPv4Unspecified(), 0),
			netip.PrefixFrom(netip.IPv6Unspecified(), 0),
		}
		if len(options.Peers) > 0 {
			for _, peer := range options.Peers {
				if len(peer.AllowedIPs) == 0 {
					peer.AllowedIPs = option.LegacyListable[netip.Prefix](allTraffic)
				}
				endpointOptions.Peers = append(endpointOptions.Peers, peer)
			}
		} else {
			endpointOptions.Peers = []option.WireGuardPeer{{
				Address:      options.Server,
				Port:         options.ServerPort,
				PublicKey:    options.PeerPublicKey,
				PreSharedKey: options.PreSharedKey,
				Reserved:     options.Reserved,
				AllowedIPs:   option.LegacyListable[netip.Prefix](allTraffic),
			}}
		}
		return wireguard.NewEndpoint(ctx, router, logger, tag, endpointOptions)
	})
}
