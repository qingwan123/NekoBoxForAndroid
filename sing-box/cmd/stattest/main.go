// 临时工具：验证 sing-box 1.14 的 v2ray stats 流量统计链路是否真的工作。
// 起一个 box（mixed inbound + direct outbound），注册 SbV2rayServer，
// 通过 socks5 代理发一次真实请求，然后查询 uplink/downlink 计数器。
package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
)

const configJSON = `{
  "log": {"level": "warn"},
  "dns": {
    "servers": [
      {"type": "local", "tag": "dns-local"}
    ],
    "final": "dns-local"
  },
  "inbounds": [
    {"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": 16451}
  ],
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {"type": "selector", "tag": "proxy", "outbounds": ["direct"], "default": "direct"}
  ],
  "route": {
    "final": "proxy"
  }
}`

func main() {
	// 目标服务
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "hello-from-target-server-0123456789")
	}))
	defer srv.Close()

	ctx := include.Context(context.Background())
	var opts option.Options
	if err := opts.UnmarshalJSONContext(ctx, []byte(configJSON)); err != nil {
		fmt.Println("config invalid:", err)
		os.Exit(1)
	}
	instance, err := box.New(box.Options{Options: opts, Context: ctx})
	if err != nil {
		fmt.Println("new failed:", err)
		os.Exit(1)
	}
	defer instance.Close()

	// 模拟 libcore 的 SetV2rayStats
	v2server := boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: []string{"proxy", "direct"},
	})
	if v2server == nil || v2server.StatsService() == nil {
		fmt.Println("FAIL: SbV2rayServer/StatsService is nil")
		os.Exit(1)
	}
	instance.Router().AppendTracker(v2server.StatsService())

	if err := instance.Start(); err != nil {
		fmt.Println("start failed:", err)
		os.Exit(1)
	}
	fmt.Println("OK: box started")

	proxyURL, _ := url.Parse("socks5://127.0.0.1:16451")
	client := &http.Client{
		Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)},
		Timeout:   10 * time.Second,
	}
	body := ""
	for i := 0; i < 3; i++ {
		resp, err := client.Get(srv.URL)
		if err != nil {
			fmt.Println("request failed:", err)
			os.Exit(1)
		}
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		body = string(b)
	}
	fmt.Println("OK: request through proxy, body len:", len(body))

	time.Sleep(300 * time.Millisecond)

	for _, tag := range []string{"proxy", "direct"} {
		up := v2server.QueryStats("outbound>>>" + tag + ">>>traffic>>>uplink")
		down := v2server.QueryStats("outbound>>>" + tag + ">>>traffic>>>downlink")
		fmt.Printf("tag=%-8s uplink=%d downlink=%d\n", tag, up, down)
	}
	up := v2server.QueryStats("outbound>>>proxy>>>traffic>>>uplink") +
		v2server.QueryStats("outbound>>>direct>>>traffic>>>uplink")
	down := v2server.QueryStats("outbound>>>proxy>>>traffic>>>downlink") +
		v2server.QueryStats("outbound>>>direct>>>traffic>>>downlink")
	if up == 0 && down == 0 {
		fmt.Println("FAIL: counters stayed at zero")
		os.Exit(1)
	}
	fmt.Println("OK: traffic counters working")
}
