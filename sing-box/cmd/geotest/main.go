// 临时工具：验证 NekoBox 内置的 geoip.db / geosite.db 能否被内核正常加载。
package main

import (
	"fmt"
	"net"
	"os"

	"github.com/oschwald/maxminddb-golang"
	"github.com/sagernet/sing-box/common/geosite"
)

func main() {
	dir := `C:\Users\zmyloy\dev\geotest`
	fail := false

	// geoip: MaxMind DB
	reader, err := maxminddb.Open(dir + `\geoip.db`)
	if err != nil {
		fmt.Println("FAIL geoip open:", err)
		fail = true
	} else {
		var record string
		netstr := "114.114.114.114"
		err = reader.Lookup(net.ParseIP(netstr), &record)
		if err != nil {
			fmt.Println("FAIL geoip lookup:", err)
			fail = true
		} else {
			fmt.Printf("OK geoip: %s -> %s\n", netstr, record)
		}
		reader.Close()
	}

	// geosite: sing-box reader
	gsReader, codes, err := geosite.Open(dir + `\geosite.db`)
	if err != nil {
		fmt.Println("FAIL geosite open:", err)
		fail = true
	} else {
		fmt.Printf("OK geosite: %d codes loaded, sample: ", len(codes))
		for i := 0; i < 3 && i < len(codes); i++ {
			fmt.Printf("%s ", codes[i])
		}
		fmt.Println()
		items, err := gsReader.Read("cn")
		if err != nil {
			fmt.Println("FAIL geosite read cn:", err)
			fail = true
		} else {
			fmt.Printf("OK geosite: code 'cn' has %d rules\n", len(items))
		}
	}

	if fail {
		os.Exit(1)
	}
	fmt.Println("ALL OK")
}

