package config

import (
	"encoding/json"
	"testing"

	"cfa/native/app"

	mihomoConfig "github.com/metacubex/mihomo/config"
)

func TestPatchSecondHopChainsAndPrioritizesSelectedAppsAndGoogle(t *testing.T) {
	settings := app.SecondHopConfig{
		Enabled:  true,
		Server:   "proxy.example",
		Port:     7878,
		Username: "test-user",
		Password: "test-password",
		Apps:     []string{"com.example.selected", "invalid,rule"},
	}
	content, err := json.Marshal(settings)
	if err != nil {
		t.Fatalf("marshal test settings: %v", err)
	}
	if err := app.SetSecondHopConfig(string(content)); err != nil {
		t.Fatalf("set test settings: %v", err)
	}
	t.Cleanup(func() {
		_ = app.SetSecondHopConfig(`{"enabled":false}`)
	})

	rawConfig := &mihomoConfig.RawConfig{
		Proxy: []map[string]any{{"name": "first-node", "type": "http", "server": "first.example", "port": 80}},
		ProxyGroup: []map[string]any{{
			"name":    "First Layer",
			"type":    "select",
			"proxies": []string{"first-node"},
		}},
		Rule: []string{"DOMAIN,existing.example,DIRECT", "MATCH,First Layer"},
	}

	if err := patchSecondHop(rawConfig, ""); err != nil {
		t.Fatalf("patch second-hop routes: %v", err)
	}
	if len(rawConfig.Proxy) != 2 {
		t.Fatalf("expected second-hop proxy to be injected, got %d proxies", len(rawConfig.Proxy))
	}
	secondHop := rawConfig.Proxy[1]
	if secondHop["type"] != "http" || secondHop["dialer-proxy"] != "First Layer" {
		t.Fatalf("second-hop proxy is not chained through the first layer: %#v", secondHop)
	}
	if rawConfig.Rule[0] != "AND,((PROCESS-NAME,com.example.selected),(NETWORK,udp)),REJECT" {
		t.Fatalf("selected-app UDP guard is not first: %q", rawConfig.Rule[0])
	}
	if rawConfig.Rule[1] != "AND,((GEOSITE,google),(NETWORK,udp)),REJECT" || rawConfig.Rule[2] != "GEOSITE,google,CFA Second Hop" {
		t.Fatalf("Google UDP guard or TCP rule is not before existing rules: %#v", rawConfig.Rule[:3])
	}
	if rawConfig.Mode.String() != "rule" {
		t.Fatalf("second-hop routing did not enable rule mode: %s", rawConfig.Mode.String())
	}
	if rawConfig.Rule[len(rawConfig.Rule)-1] != "MATCH,First Layer" {
		t.Fatalf("existing fallback rule was not retained: %q", rawConfig.Rule[len(rawConfig.Rule)-1])
	}
}
