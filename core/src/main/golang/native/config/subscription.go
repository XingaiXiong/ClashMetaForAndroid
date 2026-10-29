package config

import (
	"encoding/base64"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
)

var errNotProxySubscription = errors.New("not a supported proxy subscription")

func parseProxySubscription(data []byte) (*config.RawConfig, error) {
	payload, ok := decodeProxySubscription(data)
	if !ok {
		return nil, errNotProxySubscription
	}

	proxies := make([]map[string]any, 0)
	proxyNames := make([]string, 0)
	usedNames := make(map[string]struct{})
	skipped := 0

	for index, line := range strings.Split(payload, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}

		proxy, err := parseProxyURI(line, index+1)
		if err != nil {
			skipped++
			continue
		}

		name := proxy["name"].(string)
		baseName := name
		for suffix := 2; ; suffix++ {
			if _, exists := usedNames[name]; !exists {
				break
			}
			name = fmt.Sprintf("%s (%d)", baseName, suffix)
		}
		usedNames[name] = struct{}{}
		proxy["name"] = name
		proxyNames = append(proxyNames, name)
		proxies = append(proxies, proxy)
	}

	if len(proxies) == 0 {
		return nil, errNotProxySubscription
	}

	const firstLayerGroup = "URLClash First Layer"
	rawConfig := config.DefaultRawConfig()
	rawConfig.Proxy = proxies
	rawConfig.ProxyGroup = []map[string]any{{
		"name":    firstLayerGroup,
		"type":    "select",
		"proxies": proxyNames,
	}}
	rawConfig.Rule = []string{"MATCH," + firstLayerGroup}

	if skipped > 0 {
		// Do not include subscription content in logs: URI lines may contain credentials.
		log.Warnln("Skipped %d unsupported or invalid proxy subscription entries", skipped)
	}
	log.Infoln("Imported proxy subscription with %d supported nodes", len(proxies))

	return rawConfig, nil
}

func decodeProxySubscription(data []byte) (string, bool) {
	payload := strings.TrimSpace(string(data))
	if !utf8.ValidString(payload) {
		return "", false
	}

	if !strings.Contains(payload, "://") {
		encoded := strings.Join(strings.Fields(payload), "")
		var decoded []byte
		var err error
		for _, decoder := range []*base64.Encoding{
			base64.StdEncoding,
			base64.RawStdEncoding,
			base64.URLEncoding,
			base64.RawURLEncoding,
		} {
			decoded, err = decoder.DecodeString(encoded)
			if err == nil {
				break
			}
		}
		if err != nil || !utf8.Valid(decoded) {
			return "", false
		}
		payload = string(decoded)
	}

	if !strings.Contains(payload, "://") {
		return "", false
	}
	return payload, true
}

func parseProxyURI(line string, index int) (map[string]any, error) {
	u, err := url.Parse(line)
	if err != nil {
		return nil, err
	}

	scheme := strings.ToLower(u.Scheme)
	server := u.Hostname()
	port, err := strconv.Atoi(u.Port())
	if err != nil || server == "" || port < 1 || port > 65535 {
		return nil, errors.New("invalid proxy endpoint")
	}

	name := strings.TrimSpace(u.Fragment)
	if name == "" {
		name = fmt.Sprintf("%s %d", scheme, index)
	}
	proxy := map[string]any{
		"name":   name,
		"type":   scheme,
		"server": server,
		"port":   port,
	}
	query := u.Query()

	switch scheme {
	case "vless":
		if u.User == nil || u.User.Username() == "" {
			return nil, errors.New("missing VLESS UUID")
		}
		proxy["uuid"] = u.User.Username()
		if value := query.Get("flow"); value != "" {
			proxy["flow"] = value
		}
		if value := query.Get("encryption"); value != "" {
			proxy["encryption"] = value
		}

		security := strings.ToLower(query.Get("security"))
		if security == "tls" || security == "reality" {
			proxy["tls"] = true
			if value := query.Get("sni"); value != "" {
				proxy["servername"] = value
			}
			if value := query.Get("fp"); value != "" {
				proxy["client-fingerprint"] = value
			}
			if value := query.Get("alpn"); value != "" {
				proxy["alpn"] = splitCSV(value)
			}
			if queryBool(query.Get("insecure")) {
				proxy["skip-cert-verify"] = true
			}
		}
		if security == "reality" {
			reality := map[string]any{}
			if value := query.Get("pbk"); value != "" {
				reality["public-key"] = value
			}
			if value := query.Get("sid"); value != "" {
				reality["short-id"] = value
			}
			if len(reality) > 0 {
				proxy["reality-opts"] = reality
			}
		}

		switch strings.ToLower(query.Get("type")) {
		case "", "tcp":
			if strings.EqualFold(query.Get("headerType"), "http") {
				proxy["network"] = "http"
				proxy["http-opts"] = httpOptions(query.Get("path"), query.Get("host"))
			}
		case "ws":
			proxy["network"] = "ws"
			options := map[string]any{}
			if value := query.Get("path"); value != "" {
				options["path"] = value
			}
			if value := query.Get("host"); value != "" {
				options["headers"] = map[string]string{"Host": value}
			}
			if len(options) > 0 {
				proxy["ws-opts"] = options
			}
		case "grpc":
			proxy["network"] = "grpc"
			options := map[string]any{}
			if value := query.Get("serviceName"); value != "" {
				options["grpc-service-name"] = value
			}
			proxy["grpc-opts"] = options
		case "http":
			proxy["network"] = "http"
			proxy["http-opts"] = httpOptions(query.Get("path"), query.Get("host"))
		case "h2":
			proxy["network"] = "h2"
			options := map[string]any{}
			if value := query.Get("host"); value != "" {
				options["host"] = splitCSV(value)
			}
			if value := query.Get("path"); value != "" {
				options["path"] = value
			}
			proxy["h2-opts"] = options
		case "xhttp":
			proxy["network"] = "xhttp"
			options := map[string]any{}
			for _, key := range []string{"path", "mode"} {
				if value := query.Get(key); value != "" {
					options[key] = value
				}
			}
			if value := query.Get("host"); value != "" {
				options["host"] = value
			}
			proxy["xhttp-opts"] = options
		default:
			return nil, errors.New("unsupported VLESS transport")
		}

	case "hysteria2":
		if u.User == nil {
			return nil, errors.New("missing Hysteria2 password")
		}
		password, hasPassword := u.User.Password()
		if !hasPassword {
			password = u.User.Username()
		}
		if password == "" {
			return nil, errors.New("missing Hysteria2 password")
		}
		proxy["password"] = password
		if value := query.Get("sni"); value != "" {
			proxy["sni"] = value
		}
		if value := query.Get("mport"); value != "" {
			proxy["ports"] = value
		}
		if queryBool(query.Get("insecure")) {
			proxy["skip-cert-verify"] = true
		}
		if value := query.Get("pinSHA256"); value != "" {
			proxy["fingerprint"] = value
		}
		if value := query.Get("obfs"); value != "" {
			proxy["obfs"] = value
		}
		if value := query.Get("obfs-password"); value != "" {
			proxy["obfs-password"] = value
		}
	default:
		return nil, errors.New("unsupported proxy URI scheme")
	}

	return proxy, nil
}

func httpOptions(path, host string) map[string]any {
	options := map[string]any{}
	if path != "" {
		options["path"] = []string{path}
	}
	if host != "" {
		options["headers"] = map[string][]string{"Host": {host}}
	}
	return options
}

func queryBool(value string) bool {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "1", "true", "yes":
		return true
	default:
		return false
	}
}

func splitCSV(value string) []string {
	values := strings.Split(value, ",")
	result := make([]string, 0, len(values))
	for _, item := range values {
		if item = strings.TrimSpace(item); item != "" {
			result = append(result, item)
		}
	}
	return result
}
