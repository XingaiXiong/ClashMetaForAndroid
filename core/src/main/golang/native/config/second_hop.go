package config

import (
	"fmt"
	"regexp"
	"strings"

	"cfa/native/app"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
	T "github.com/metacubex/mihomo/tunnel"
)

const secondHopProxyBaseName = "CFA Second Hop"

var androidPackageName = regexp.MustCompile(`^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$`)

func patchSecondHop(rawConfig *config.RawConfig, _ string) error {
	settings := app.SecondHopSettings()
	if !settings.Enabled {
		return nil
	}
	if strings.TrimSpace(settings.Server) == "" || settings.Port < 1 || settings.Port > 65535 {
		log.Warnln("Second-hop proxy is enabled but its endpoint is invalid")
		return nil
	}

	firstLayer := findFirstLayerTarget(rawConfig)
	if firstLayer == "" {
		log.Warnln("Second-hop proxy was not added because the profile has no usable first-layer proxy group")
		return nil
	}
	rawConfig.Mode = T.Rule

	proxyName := uniqueProxyName(rawConfig, secondHopProxyBaseName)
	rawConfig.Proxy = append(rawConfig.Proxy, map[string]any{
		"name":        proxyName,
		"type":        "http",
		"server":      settings.Server,
		"port":        settings.Port,
		"username":    settings.Username,
		"password":    settings.Password,
		"dialer-proxy": firstLayer,
	})

	addedRules := make([]string, 0, len(settings.Apps)*2+24+len(rawConfig.Rule))
	appRules := make([]string, 0, len(settings.Apps))
	seenApps := make(map[string]struct{}, len(settings.Apps))
	for _, packageName := range settings.Apps {
		packageName = strings.TrimSpace(packageName)
		if !androidPackageName.MatchString(packageName) {
			continue
		}
		if _, exists := seenApps[packageName]; exists {
			continue
		}
		seenApps[packageName] = struct{}{}
		addedRules = append(addedRules, fmt.Sprintf("AND,((PROCESS-NAME,%s),(NETWORK,udp)),REJECT", packageName))
		appRules = append(appRules, fmt.Sprintf("PROCESS-NAME,%s,%s", packageName, proxyName))
	}

	googleMatchers := []string{
		"GEOSITE,google",
		"GEOSITE,youtube",
		"DOMAIN-SUFFIX,google.com",
		"DOMAIN-SUFFIX,googleapis.com",
		"DOMAIN-SUFFIX,googleapis.cn",
		"DOMAIN-SUFFIX,gstatic.com",
		"DOMAIN-SUFFIX,googleusercontent.com",
		"DOMAIN-SUFFIX,googlevideo.com",
		"DOMAIN-SUFFIX,ggpht.com",
		"DOMAIN-SUFFIX,ytimg.com",
	}
	for _, matcher := range googleMatchers {
		addedRules = append(addedRules, fmt.Sprintf("AND,((%s),(NETWORK,udp)),REJECT", matcher))
		addedRules = append(addedRules, matcher+","+proxyName)
	}
	addedRules = append(addedRules, appRules...)
	rawConfig.Rule = append(addedRules, rawConfig.Rule...)

	log.Infoln("Added TCP second-hop routing for %d selected applications and Google traffic; matching UDP is blocked", len(seenApps))
	return nil
}

func findFirstLayerTarget(rawConfig *config.RawConfig) string {
	for index := len(rawConfig.Rule) - 1; index >= 0; index-- {
		parts := strings.Split(rawConfig.Rule[index], ",")
		if len(parts) < 2 {
			continue
		}
		kind := strings.ToUpper(strings.TrimSpace(parts[0]))
		if kind != "MATCH" && kind != "FINAL" {
			continue
		}
		target := strings.TrimSpace(parts[len(parts)-1])
		if usableProxyTarget(rawConfig, target) {
			return target
		}
	}

	for _, preferred := range []string{"PROXY", "Proxy", "proxy", "GLOBAL", "全局选择", "节点选择", "🚀 节点选择"} {
		if usableProxyTarget(rawConfig, preferred) {
			return preferred
		}
	}

	for _, group := range rawConfig.ProxyGroup {
		name, _ := group["name"].(string)
		if usableProxyTarget(rawConfig, name) {
			return name
		}
	}

	for _, proxy := range rawConfig.Proxy {
		name, _ := proxy["name"].(string)
		if name != "" && usableProxyTarget(rawConfig, name) {
			return name
		}
	}

	return ""
}

func usableProxyTarget(rawConfig *config.RawConfig, name string) bool {
	if name == "" {
		return false
	}
	switch strings.ToUpper(name) {
	case "DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE":
		return false
	}

	for _, group := range rawConfig.ProxyGroup {
		if groupName, _ := group["name"].(string); groupName == name {
			return true
		}
	}
	for _, proxy := range rawConfig.Proxy {
		if proxyName, _ := proxy["name"].(string); proxyName == name {
			return true
		}
	}
	return false
}

func uniqueProxyName(rawConfig *config.RawConfig, base string) string {
	nameExists := func(name string) bool {
		for _, proxy := range rawConfig.Proxy {
			if proxyName, _ := proxy["name"].(string); proxyName == name {
				return true
			}
		}
		for _, group := range rawConfig.ProxyGroup {
			if groupName, _ := group["name"].(string); groupName == name {
				return true
			}
		}
		if _, exists := rawConfig.ProxyProvider[name]; exists {
			return true
		}
		return false
	}

	name := base
	for suffix := 2; nameExists(name); suffix++ {
		name = fmt.Sprintf("%s (%d)", base, suffix)
	}
	return name
}
