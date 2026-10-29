package app

import (
	"encoding/json"
	"strconv"
	"strings"
	"sync"
	"time"
)

var appVersionName string
var platformVersion int
var installedAppsUid = map[int]string{}

type SecondHopConfig struct {
	Enabled  bool     `json:"enabled"`
	Server   string   `json:"server"`
	Port     int      `json:"port"`
	Username string   `json:"username"`
	Password string   `json:"password"`
	Apps     []string `json:"apps"`
}

var secondHopConfigLock sync.RWMutex
var secondHopConfig SecondHopConfig

func ApplyVersionName(versionName string) {
	appVersionName = versionName
}

func ApplyPlatformVersion(version int) {
	platformVersion = version
}

func VersionName() string {
	return appVersionName
}

func PlatformVersion() int {
	return platformVersion
}

func NotifyInstallAppsChanged(uidList string) {
	uids := map[int]string{}

	for _, item := range strings.Split(uidList, ",") {
		kv := strings.Split(item, ":")
		if len(kv) == 2 {
			uid, err := strconv.Atoi(kv[0])
			if err != nil {
				continue
			}

			uids[uid] = kv[1]
		}
	}

	installedAppsUid = uids
}

func QueryAppByUid(uid int) string {
	return installedAppsUid[uid]
}

func SetSecondHopConfig(content string) error {
	var config SecondHopConfig
	if err := json.Unmarshal([]byte(content), &config); err != nil {
		return err
	}

	secondHopConfigLock.Lock()
	secondHopConfig = config
	secondHopConfigLock.Unlock()
	return nil
}

func SecondHopSettings() SecondHopConfig {
	secondHopConfigLock.RLock()
	defer secondHopConfigLock.RUnlock()

	config := secondHopConfig
	config.Apps = append([]string(nil), secondHopConfig.Apps...)
	return config
}

func NotifyTimeZoneChanged(name string, offset int) {
	time.Local = time.FixedZone(name, offset)
}
