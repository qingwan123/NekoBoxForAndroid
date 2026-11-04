// 临时工具：用 sing-box 1.14 的 option 解析器校验一份配置 JSON。
// 用于在无设备的情况下验证 NekoBox 生成的配置字段是否仍被 1.14 接受。
package main

import (
	"context"
	"fmt"
	"os"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
)

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
	ctx := include.Context(context.Background())
	var opts option.Options
	if err := opts.UnmarshalJSONContext(ctx, data); err != nil {
		fmt.Println("INVALID:", err)
		os.Exit(1)
	}
	fmt.Println("OK: config parsed")

	// RUNTIME=1 时真实初始化一次 box，可捕获 "initialize inbound[0]: ..." 这类错误
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

	// RUNTIME=2 时继续 Start，可捕获 "start dns/https[...]: detour to an empty
	// direct outbound makes no sense" 这类只在启动阶段才检查的错误
	if os.Getenv("RUNTIME") == "2" {
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
