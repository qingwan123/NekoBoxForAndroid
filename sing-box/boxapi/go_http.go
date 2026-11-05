package boxapi

import (
	"context"
	"net"
	"net/http"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
)

func CreateProxyHttpClient(box *box.Box, tracker adapter.ConnectionTracker) *http.Client {
	transport := &http.Transport{
		TLSHandshakeTimeout:   time.Second * 3,
		ResponseHeaderTimeout: time.Second * 3,
	}

	if box != nil {
		transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			return DialContext(ctx, box, tracker, network, addr)
		}
	}

	client := &http.Client{
		Transport: transport,
	}

	return client
}
// CreateProxyHttpClientWithTag 返回固定走某个 outbound 的 HTTP 客户端（用于批量测速）。
func CreateProxyHttpClientWithTag(box *box.Box, tracker adapter.ConnectionTracker, outboundTag string) *http.Client {
	transport := &http.Transport{
		TLSHandshakeTimeout:   time.Second * 3,
		ResponseHeaderTimeout: time.Second * 3,
	}
	transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		return DialContextWithTag(ctx, box, tracker, outboundTag, network, addr)
	}
	return &http.Client{Transport: transport}
}
