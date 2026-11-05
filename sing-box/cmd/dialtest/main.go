package main

// dialtest: load a sing-box config, start a real instance, and make one HTTP
// request through a specific outbound. Used to tell "the node is unreachable"
// apart from "the app generated a broken config".

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"os"
	"time"

	"github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/json"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: dialtest <config.json> [outbound-tag] [url]")
		os.Exit(1)
	}
	tag := "proxy"
	if len(os.Args) > 2 {
		tag = os.Args[2]
	}
	target := "https://www.google.com/generate_204"
	if len(os.Args) > 3 {
		target = os.Args[3]
	}

	content, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Println("FAIL read:", err)
		os.Exit(1)
	}
	ctx, cancel := context.WithTimeout(include.Context(context.Background()), 40*time.Second)
	defer cancel()

	var options option.Options
	if options, err = json.UnmarshalExtendedContext[option.Options](ctx, content); err != nil {
		fmt.Println("FAIL parse:", err)
		os.Exit(1)
	}

	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		fmt.Println("FAIL new:", err)
		os.Exit(1)
	}
	if err = instance.Start(); err != nil {
		fmt.Println("FAIL start:", err)
		os.Exit(1)
	}
	defer instance.Close()
	fmt.Println("OK: box started")

	outbound, ok := instance.Outbound().Outbound(tag)
	if !ok {
		fmt.Println("FAIL: no outbound", tag)
		os.Exit(1)
	}

	client := &http.Client{
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return outbound.DialContext(ctx, network, M.ParseSocksaddr(addr))
			},
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		},
	}
	start := time.Now()
	resp, err := client.Get(target)
	if err != nil {
		fmt.Println("FAIL dial:", err)
		os.Exit(1)
	}
	defer resp.Body.Close()
	fmt.Printf("OK: %s -> %d in %dms\n", target, resp.StatusCode, time.Since(start).Milliseconds())
}
