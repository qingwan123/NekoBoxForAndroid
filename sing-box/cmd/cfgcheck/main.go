// 临时工具：用当前 sing-box 的 option 解析器校验一份配置 JSON，
// 并可真实启动一次 box（RUNTIME=1/2），用于在无设备时验证 NekoBox 生成的配置。
//
// 同时注册 NekoBox 的 geoip:/geosite: 钩子，否则带内置规则集的配置会报
// "open .../geosite:xxx: no such file"（这正是切到 1.15 上游后踩到的坑）。
package main

import (
	"context"
	"fmt"
	"net/netip"
	"os"

	box "github.com/sagernet/sing-box"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
)

// 只验证「前缀被正确拦截」，规则内容用占位值即可
func setupGeoHooks() {
	nekoutils.GetGeoIPHeadlessRules = func(code string) ([]option.HeadlessRule, error) {
		fmt.Printf("  [hook] geoip:%s\n", code)
		prefix := badoption.Prefixable(netip.MustParsePrefix("0.0.0.0/0"))
		var rule option.DefaultHeadlessRule
		rule.IPCIDR = badoption.Listable[*badoption.Prefixable]{&prefix}
		return []option.HeadlessRule{{Type: C.RuleTypeDefault, DefaultOptions: rule}}, nil
	}
	nekoutils.GetGeoSiteHeadlessRules = func(code string) ([]option.HeadlessRule, error) {
		fmt.Printf("  [hook] geosite:%s\n", code)
		var rule option.DefaultHeadlessRule
		rule.DomainSuffix = []string{"placeholder.invalid"}
		return []option.HeadlessRule{{Type: C.RuleTypeDefault, DefaultOptions: rule}}, nil
	}
}

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: cfgcheck <config.json>")
		os.Exit(2)
	}
	data, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Println("read:", err)
		os.Exit(2)
	}
	setupGeoHooks()
	ctx := include.Context(context.Background())
	var opts option.Options
	if err := opts.UnmarshalJSONContext(ctx, data); err != nil {
		fmt.Println("INVALID:", err)
		os.Exit(1)
	}
	fmt.Println("OK: config parsed")

	runtimeMode := os.Getenv("RUNTIME")
	if runtimeMode == "" || runtimeMode == "0" {
		return
	}
	instance, err := box.New(box.Options{Options: opts, Context: ctx})
	if err != nil {
		fmt.Println("INIT FAILED:", err)
		os.Exit(1)
	}
	defer instance.Close()
	fmt.Println("OK: box initialized")

	if runtimeMode == "2" {
		if err := instance.Start(); err != nil {
			fmt.Println("START FAILED:", err)
			os.Exit(1)
		}
		fmt.Println("OK: box started")
		if def := instance.Outbound().Default(); def != nil {
			fmt.Println("default outbound:", def.Tag())
		} else {
			fmt.Println("default outbound: <nil>")
		}
	}
}
