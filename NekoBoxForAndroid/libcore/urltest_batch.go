package libcore

import (
	"strings"
	"log"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"

	"libcore/device"
)

// UrlTestBatchResult 是单个节点的测速结果。
type UrlTestBatchResult struct {
	Tag     string `json:"tag"`
	Latency int32  `json:"latency"`
	Error   string `json:"error,omitempty"`
}

// UrlTestBatch 在**同一个** box 实例里并发测试多个 outbound。
//
// NekoBox 原来的做法是给每个节点单独启动一个 sing-box 实例再测（init + start + close），
// 节点一多就非常慢。这里改成 Clash 的方式：一次启动，按 outbound tag 并发拨号。
//
// 参数：
//   - instance: 已构建好、包含所有待测节点 outbound 的实例（需已 Start）
//   - tagsJSON: 待测 outbound tag 的 JSON 数组
//   - link:     测速用的 URL
//   - timeout:  单次超时(ms)
//   - concurrency: 并发数
//
// 返回 JSON 数组（与 tagsJSON 同序）。
//
//export UrlTestBatch
func UrlTestBatch(instance *BoxInstance, tagsJSON string, link string, timeout int32, concurrency int32) (resultJSON string) {
	defer device.DeferPanicToError("UrlTestBatch", func(err error) {
		resultJSON = fmt.Sprintf(`{"error":%q}`, err.Error())
	})
	var tags []string
	if err := json.Unmarshal([]byte(tagsJSON), &tags); err != nil {
		resultJSON = fmt.Sprintf(`{"error":%q}`, "invalid tags: "+err.Error())
		return
	}
	if instance == nil || instance.Box == nil {
		resultJSON = `{"error":"instance not started"}`
		return
	}
	concurrencyCount := int(concurrency)
	if concurrencyCount <= 0 {
		concurrencyCount = 8
	}
	if concurrencyCount > len(tags) {
		concurrencyCount = len(tags)
	}

	var tracker adapter.ConnectionTracker
	if instance.v2api != nil {
		tracker = instance.v2api.StatsService()
	}

	results := make([]UrlTestBatchResult, len(tags))
	sem := make(chan struct{}, concurrencyCount)
	var wg sync.WaitGroup
	for i, tag := range tags {
		wg.Add(1)
		go func(index int, tag string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			results[index].Tag = tag
			client := boxapi.CreateProxyHttpClientWithTag(instance.Box, tracker, tag)
			latency, err := speedtest.UrlTest(client, link, timeout, speedtest.UrlTestStandard_RTT)
			if err != nil {
				results[index].Error = err.Error()
			}
			results[index].Latency = latency
		}(i, tag)
	}
	wg.Wait()

	content, err := json.Marshal(results)
	if err != nil {
		resultJSON = fmt.Sprintf(`{"error":%q}`, err.Error())
		return
	}
	resultJSON = string(content)
	return
}
// UrlTestTag 在已启动的实例里测**单个** outbound（-1 表示失败）。
// 与 UrlTestBatch 相比，这个可以由 App 侧自己控制并发并逐个回显结果。
//
//export UrlTestTag
func UrlTestTag(instance *BoxInstance, tag string, link string, timeout int32) (latency int32) {
	defer device.DeferPanicToError("UrlTestTag", func(err error) {
		latency = -1
	})
	if instance == nil || instance.Box == nil {
		return -1
	}
	var tracker adapter.ConnectionTracker
	if instance.v2api != nil {
		tracker = instance.v2api.StatsService()
	}
	client := boxapi.CreateProxyHttpClientWithTag(instance.Box, tracker, tag)
	result, err := speedtest.UrlTest(client, link, timeout, speedtest.UrlTestStandard_RTT)
	if err != nil {
		return -1
	}
	return result
}
// UrlTestTagMain 在**正在运行的主实例**里测一个 outbound（-1 表示失败或未启动）。
//
// 连接代理时不能新建测速实例：新实例的 socket 会被自己的 tun 捕获，测速必然超时。
// 这里直接复用主实例的 outbound 拨号，测的就是当前代理本身。
//
//export UrlTestTagMain
func UrlTestTagMain(tag string, link string, timeout int32) int32 {
	if mainInstance == nil {
		return -1
	}
	return UrlTestTag(mainInstance, tag, link, timeout)
}
// UrlTestNodeMain 在主实例里按**节点 id** 找 outbound 并测速（-1 = 失败/未启动）。
//
// 不直接拼 tag：ConfigBuilder 里节点 outbound 的 tag 是 c-<chainId>-<profileId>，
// 但链式/复用情况下并不总是 c-<id>-<id>。这里按「前缀 c-<id>- 且后缀 -<id>」匹配，
// 避免猜错 tag 导致全部节点瞬间超时。
//
//export UrlTestNodeMain
func UrlTestNodeMain(profileId int64, link string, timeout int32) int32 {
	if mainInstance == nil || mainInstance.Box == nil {
		return -1
	}
	prefix := fmt.Sprintf("c-%d-", profileId)
	suffix := fmt.Sprintf("-%d", profileId)
	target := ""
	for _, outbound := range mainInstance.Box.Outbound().Outbounds() {
		tag := outbound.Tag()
		if strings.HasPrefix(tag, prefix) && strings.HasSuffix(tag, suffix) {
			target = tag
			break
		}
	}
	if target == "" {
		log.Println("UrlTestNodeMain: no outbound for profile", profileId)
		return -1
	}
	return UrlTestTag(mainInstance, target, link, timeout)
}
