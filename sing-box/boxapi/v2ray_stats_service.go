package boxapi

import (
	"context"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	N "github.com/sagernet/sing/common/network"
)

type SbStatsService struct {
	createdAt time.Time
	inbounds  map[string]bool
	outbounds map[string]bool
	users     map[string]bool
	access    sync.Mutex
	counters  map[string]*atomic.Int64
}

// resolveStatsOutbound 解析出「真正承载流量」的 outbound tag。
// 1.13+ 的 route 把 selector/urltest 组当作匹配到的 outbound 传进来，
// 但 NekoBox 是按具体节点 tag 查询流量的 —— 直接用组 tag 统计会永远查不到。
func resolveStatsOutbound(matchOutbound adapter.Outbound) string {
	if matchOutbound == nil {
		return ""
	}
	tag := matchOutbound.Tag()
	if group, isGroup := matchOutbound.(interface{ Now() string }); isGroup {
		if now := group.Now(); now != "" {
			tag = now
		}
	}
	return tag
}

func NewSbStatsService(options option.V2RayStatsServiceOptions) *SbStatsService {
	if !options.Enabled {
		return nil
	}
	inbounds := make(map[string]bool)
	outbounds := make(map[string]bool)
	users := make(map[string]bool)
	for _, inbound := range options.Inbounds {
		inbounds[inbound] = true
	}
	for _, outbound := range options.Outbounds {
		outbounds[outbound] = true
	}
	for _, user := range options.Users {
		users[user] = true
	}
	return &SbStatsService{
		createdAt: time.Now(),
		inbounds:  inbounds,
		outbounds: outbounds,
		users:     users,
		counters:  make(map[string]*atomic.Int64),
	}
}

func (s *SbStatsService) RoutedConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) net.Conn {
	inbound := metadata.Inbound
	user := metadata.User
	outbound := resolveStatsOutbound(matchOutbound)
	return s.RoutedConnectionInternal(inbound, outbound, user, conn, true)
}

func (s *SbStatsService) RoutedConnectionInternal(inbound string, outbound string, user string, conn net.Conn, directIn bool) net.Conn {
	var readCounter []*atomic.Int64
	var writeCounter []*atomic.Int64
	countInbound := inbound != "" && s.inbounds[inbound]
	countOutbound := outbound != "" && s.outbounds[outbound]
	countUser := user != "" && s.users[user]
	if !countInbound && !countOutbound && !countUser {
		return conn
	}
	s.access.Lock()
	if countInbound {
		readCounter = append(readCounter, s.loadOrCreateCounter("inbound>>>"+inbound+">>>traffic>>>uplink"))
		writeCounter = append(writeCounter, s.loadOrCreateCounter("inbound>>>"+inbound+">>>traffic>>>downlink"))
	}
	if countOutbound {
		readCounter = append(readCounter, s.loadOrCreateCounter("outbound>>>"+outbound+">>>traffic>>>uplink"))
		writeCounter = append(writeCounter, s.loadOrCreateCounter("outbound>>>"+outbound+">>>traffic>>>downlink"))
	}
	if countUser {
		readCounter = append(readCounter, s.loadOrCreateCounter("user>>>"+user+">>>traffic>>>uplink"))
		writeCounter = append(writeCounter, s.loadOrCreateCounter("user>>>"+user+">>>traffic>>>downlink"))
	}
	s.access.Unlock()
	if directIn {
		conn = bufio.NewInt64CounterConn(conn, readCounter, writeCounter)
	} else {
		conn = bufio.NewInt64CounterConn(conn, writeCounter, readCounter)
	}
	return conn
}

func (s *SbStatsService) RoutedPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) N.PacketConn {
	inbound := metadata.Inbound
	user := metadata.User
	outbound := resolveStatsOutbound(matchOutbound)
	var readCounter []*atomic.Int64
	var writeCounter []*atomic.Int64
	countInbound := inbound != "" && s.inbounds[inbound]
	countOutbound := outbound != "" && s.outbounds[outbound]
	countUser := user != "" && s.users[user]
	if !countInbound && !countOutbound && !countUser {
		return conn
	}
	s.access.Lock()
	if countInbound {
		readCounter = append(readCounter, s.loadOrCreateCounter("inbound>>>"+inbound+">>>traffic>>>uplink"))
		writeCounter = append(writeCounter, s.loadOrCreateCounter("inbound>>>"+inbound+">>>traffic>>>downlink"))
	}
	if countOutbound {
		readCounter = append(readCounter, s.loadOrCreateCounter("outbound>>>"+outbound+">>>traffic>>>uplink"))
		writeCounter = append(writeCounter, s.loadOrCreateCounter("outbound>>>"+outbound+">>>traffic>>>downlink"))
	}
	if countUser {
		readCounter = append(readCounter, s.loadOrCreateCounter("user>>>"+user+">>>traffic>>>uplink"))
		writeCounter = append(writeCounter, s.loadOrCreateCounter("user>>>"+user+">>>traffic>>>downlink"))
	}
	s.access.Unlock()
	return bufio.NewInt64CounterPacketConn(conn, readCounter, nil, writeCounter, nil)
}

func (s *SbStatsService) RoutedFlow(ctx context.Context, metadata adapter.InboundContext, matchedRule adapter.Rule, matchOutbound adapter.Outbound) tun.FlowTracker {
	inbound := metadata.Inbound
	user := metadata.User
	outbound := resolveStatsOutbound(matchOutbound)
	var readCounter []*atomic.Int64
	var writeCounter []*atomic.Int64
	countInbound := inbound != "" && s.inbounds[inbound]
	countOutbound := outbound != "" && s.outbounds[outbound]
	countUser := user != "" && s.users[user]
	if !countInbound && !countOutbound && !countUser {
		return nil
	}
	s.access.Lock()
	if countInbound {
		readCounter = append(readCounter, s.loadOrCreateCounter("inbound>>>"+inbound+">>>traffic>>>uplink"))
		writeCounter = append(writeCounter, s.loadOrCreateCounter("inbound>>>"+inbound+">>>traffic>>>downlink"))
	}
	if countOutbound {
		readCounter = append(readCounter, s.loadOrCreateCounter("outbound>>>"+outbound+">>>traffic>>>uplink"))
		writeCounter = append(writeCounter, s.loadOrCreateCounter("outbound>>>"+outbound+">>>traffic>>>downlink"))
	}
	if countUser {
		readCounter = append(readCounter, s.loadOrCreateCounter("user>>>"+user+">>>traffic>>>uplink"))
		writeCounter = append(writeCounter, s.loadOrCreateCounter("user>>>"+user+">>>traffic>>>downlink"))
	}
	s.access.Unlock()
	return &statsFlowTracker{
		readCounter:  readCounter,
		writeCounter: writeCounter,
	}
}

// statsFlowTracker 把 sing-box 1.13+ 的 flow 统计（tun 高速路径）折算到
// v2ray stats 的 uplink/downlink 计数器上，否则 NekoBox 的速度显示恒为 0。
type statsFlowTracker struct {
	readCounter  []*atomic.Int64
	writeCounter []*atomic.Int64
	handle       tun.FlowHandle
}

func (t *statsFlowTracker) AttachFlow(handle tun.FlowHandle) {
	t.handle = handle
}

func (t *statsFlowTracker) CountForward(n int) {
	for _, counter := range t.readCounter {
		counter.Add(int64(n))
	}
}

func (t *statsFlowTracker) CountReverse(n int) {
	for _, counter := range t.writeCounter {
		counter.Add(int64(n))
	}
}

func (t *statsFlowTracker) FlowEstablished() {
}

func (t *statsFlowTracker) CloseFlow(reason tun.FlowCloseReason) {
}

func (s *SbStatsService) GetStats(ctx context.Context, name string, reset bool) (int64, error) {
	s.access.Lock()
	counter, loaded := s.counters[name]
	s.access.Unlock()
	if !loaded {
		return 0, E.New(name, " not found.")
	}
	var value int64
	if reset {
		value = counter.Swap(0)
	} else {
		value = counter.Load()
	}
	return value, nil
}

// QueryStats

// GetSysStats

//nolint:staticcheck
func (s *SbStatsService) loadOrCreateCounter(name string) *atomic.Int64 {
	counter, loaded := s.counters[name]
	if loaded {
		return counter
	}
	counter = &atomic.Int64{}
	s.counters[name] = counter
	return counter
}
