package config

import (
	"encoding/base64"
	"testing"

	mihomoConfig "github.com/metacubex/mihomo/config"
)

func TestParseProxySubscriptionCreatesUsableFirstLayer(t *testing.T) {
	content := "vless://11111111-1111-4111-8111-111111111111@first.example:443?encryption=none&type=ws&security=tls&sni=front.example&host=front.example&path=%2Fedge#VLESS%20node\n" +
		"hysteria2://test-password@second.example:8443?sni=front.example&insecure=1#mport%20node"
	encoded := base64.StdEncoding.EncodeToString([]byte(content))

	rawConfig, err := parseProxySubscription([]byte(encoded))
	if err != nil {
		t.Fatalf("parse proxy subscription: %v", err)
	}
	if len(rawConfig.Proxy) != 2 {
		t.Fatalf("expected two proxies, got %d", len(rawConfig.Proxy))
	}
	if got := rawConfig.Proxy[0]["network"]; got != "ws" {
		t.Fatalf("expected VLESS WebSocket transport, got %#v", got)
	}
	if got := rawConfig.Proxy[1]["password"]; got != "test-password" {
		t.Fatalf("expected Hysteria2 password to be retained, got %#v", got)
	}
	if len(rawConfig.ProxyGroup) != 1 || rawConfig.ProxyGroup[0]["type"] != "select" {
		t.Fatalf("expected one first-layer selector group, got %#v", rawConfig.ProxyGroup)
	}
	if len(rawConfig.Rule) != 1 || rawConfig.Rule[0] != "MATCH,URLClash First Layer" {
		t.Fatalf("unexpected first-layer fallback rule: %#v", rawConfig.Rule)
	}
	if _, err := mihomoConfig.ParseRawConfig(rawConfig); err != nil {
		t.Fatalf("generated profile did not parse with Mihomo: %v", err)
	}
}

func TestParseProxySubscriptionRejectsUnsupportedContent(t *testing.T) {
	if _, err := parseProxySubscription([]byte("not a subscription")); err == nil {
		t.Fatal("expected unsupported content to be rejected")
	}
}
