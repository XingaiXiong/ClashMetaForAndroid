# Lark ad-plugin endpoint block

The Android build applies a built-in Mihomo rule before profile rules:

```yaml
- AND,((IP-CIDR,141.11.140.226/32,no-resolve),(DST-PORT,5567)),REJECT
```

This is a global, config-parser rule: it applies to every loaded profile and
precedes the user's rules. It rejects TCP connections only when both the
destination address and destination port match the endpoint observed in the
Firebase Test Lab log for the `ad-plugin` download. Other ports on that IP and
the separate `app-plugin` host are not matched by this rule.

The rule cannot distinguish Android processes that share the app's UID, and
it will also reject any unrelated request that happens to use this same
destination IP and port. The test verifies the target app with the unmodified
APK and records the observed endpoint; a post-change device test is required
before claiming that the ad plugin is blocked while all host features remain
available.
