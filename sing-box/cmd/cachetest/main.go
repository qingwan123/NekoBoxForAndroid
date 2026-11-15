// 临时验证工具：两个 box 实例抢同一个 cache.db（bbolt 独占 flock）会怎样。
//
// 复现「连接代理时节点测速全红」的机制：libcore 原来给所有实例都传了
// PlatformLogWriter，sing-box 于是给每个实例都建 cache file（默认 no_backup/cache.db，
// bbolt，Options.Timeout=1s）。主实例持锁时，测速实例的 box.Start() 会在
// startInternalServices(Initialize) 阶段失败。
//
// 用法: cachetest
package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"time"

	box "github.com/sagernet/sing-box"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
)

type dummyWriter struct{}

func (dummyWriter) WriteMessage(level uint8, message string) {}

func cfg(port int) string {
	return fmt.Sprintf(`{
  "log": {"level": "warn"},
  "inbounds": [{"type": "socks", "tag": "in%d", "listen": "127.0.0.1", "listen_port": %d}],
  "outbounds": [{"type": "direct", "tag": "direct"}]
}`, port, port)
}

func start(name string, port int, writer sblog.PlatformWriter) *box.Box {
	ctx := include.Context(context.Background())
	var opts option.Options
	if err := opts.UnmarshalJSONContext(ctx, []byte(cfg(port))); err != nil {
		fmt.Printf("%-28s decode failed: %v\n", name, err)
		return nil
	}
	inst, err := box.New(box.Options{Options: opts, Context: ctx, PlatformLogWriter: writer})
	if err != nil {
		fmt.Printf("%-28s new failed: %v\n", name, err)
		return nil
	}
	started := time.Now()
	err = inst.Start()
	elapsed := time.Since(started)
	if err != nil {
		fmt.Printf("%-28s START FAILED after %v: %v\n", name, elapsed.Round(time.Millisecond), err)
		_ = inst.Close()
		return nil
	}
	fmt.Printf("%-28s ok in %v\n", name, elapsed.Round(time.Millisecond))
	return inst
}

func main() {
	dir, err := os.MkdirTemp("", "cachetest")
	if err != nil {
		panic(err)
	}
	os.Chdir(dir)
	fmt.Println("workdir:", dir)

	w := sblog.PlatformWriter(dummyWriter{})

	fmt.Println("--- A: main instance WITH PlatformLogWriter (creates cache.db, holds lock)")
	a := start("A main(writer)", 21001, w)
	if a != nil {
		defer a.Close()
	}

	fmt.Println("--- B: test instance WITH PlatformLogWriter (same cache.db)")
	start("B test(writer)", 21002, w)

	fmt.Println("--- C: test instance WITHOUT PlatformLogWriter (no cache file)")
	c := start("C test(nil)", 21003, nil)
	if c != nil {
		defer c.Close()
	}

	fmt.Print("cache.db present: ")
	if st, err := os.Stat(filepath.Join(dir, "cache.db")); err == nil {
		fmt.Println("yes", st.Size(), "bytes")
	} else {
		fmt.Println("no")
	}
}
