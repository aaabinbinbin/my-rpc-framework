package com.rpc.core.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

final class RpcPropertySource {
    private final Properties properties;

    RpcPropertySource(Properties properties) {
        this.properties = properties;
    }

    String get(String key, String defaultValue) {
        // JVM（Java 虚拟机）系统属性优先级高于配置文件，便于本地调试和临时覆盖。
        return System.getProperty(key, properties.getProperty(key, defaultValue));
    }

    Integer getOptionalInt(String key) {
        String raw = get(key, null);
        return raw == null || raw.isBlank() ? null : Integer.parseInt(raw);
    }

    int getInt(String key, int defaultValue) {
        return Integer.parseInt(get(key, String.valueOf(defaultValue)));
    }

    long getLong(String key, long defaultValue) {
        return Long.parseLong(get(key, String.valueOf(defaultValue)));
    }

    boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    Boolean getOptionalBoolean(String key) {
        String raw = get(key, null);
        return raw == null || raw.isBlank() ? null : Boolean.parseBoolean(raw);
    }

    List<String> getList(String key, List<String> defaultValue) {
        String raw = get(key, "");
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        // 统一在这里处理逗号分隔列表，避免每个 binder（绑定器）重复写
        // split（拆分）/ trim（去空白）逻辑。
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());
    }

    Map<String, Integer> getIntegerMapByPrefix(String prefix) {
        Map<String, Integer> values = new java.util.HashMap<>();
        // 这类 prefix map（前缀映射）主要用于 filter order（过滤器顺序）、
        // defaultValue（默认值）等动态 key（键）场景。
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String mapKey = key.substring(prefix.length()).trim();
            if (mapKey.isEmpty()) {
                continue;
            }
            values.put(mapKey, Integer.parseInt(get(key, "0")));
        }
        return values;
    }

    Map<String, String> getStringMapByPrefix(String prefix) {
        Map<String, String> values = new java.util.HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String mapKey = key.substring(prefix.length()).trim();
            if (mapKey.isEmpty()) {
                continue;
            }
            values.put(mapKey, get(key, null));
        }
        return values;
    }
}
