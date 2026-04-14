package com.rpc.core.config.source;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * RPC 配置读取适配器。
 *
 * 所处阶段：各配置 Binder 从 Properties 中读取字符串配置时。
 * 主要职责：统一处理默认值、系统属性覆盖、基础类型转换、逗号列表解析和前缀 Map 解析。
 *
 * 注意事项：这里不做业务语义校验，例如线程数是否合理、端口是否可用，这些由配置对象默认值或使用端兜底。
 */
public final class RpcPropertySource {
    /** classpath 配置文件读取出的原始属性集合。 */
    private final Properties properties;

    /**
     * 创建配置读取器。
     */
    public RpcPropertySource(Properties properties) {
        this.properties = properties;
    }

    /**
     * 获取字符串配置。
     *
     * 边界处理：JVM 系统属性优先级高于配置文件，便于本地调试、压测和线上临时覆盖。
     */
    public String get(String key, String defaultValue) {
        // JVM（Java 虚拟机）系统属性优先级高于配置文件，便于本地调试和临时覆盖。
        return System.getProperty(key, properties.getProperty(key, defaultValue));
    }

    /**
     * 获取可选 int 配置。
     *
     * 边界处理：空值返回 null，表示不覆盖默认配置。
     */
    public Integer getOptionalInt(String key) {
        String raw = get(key, null);
        return raw == null || raw.isBlank() ? null : Integer.parseInt(raw);
    }

    /**
     * 获取 int 配置，缺省时使用默认值。
     */
    public int getInt(String key, int defaultValue) {
        return Integer.parseInt(get(key, String.valueOf(defaultValue)));
    }

    /**
     * 获取 long 配置，缺省时使用默认值。
     */
    public long getLong(String key, long defaultValue) {
        return Long.parseLong(get(key, String.valueOf(defaultValue)));
    }

    /**
     * 获取 boolean 配置，缺省时使用默认值。
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    /**
     * 获取可选 boolean 配置。
     *
     * 边界处理：空值返回 null，表示不覆盖全局默认值。
     */
    public Boolean getOptionalBoolean(String key) {
        String raw = get(key, null);
        return raw == null || raw.isBlank() ? null : Boolean.parseBoolean(raw);
    }

    /**
     * 获取逗号分隔的字符串列表。
     *
     * 边界处理：空配置返回调用方提供的默认列表；每个元素会 trim 并过滤空串。
     */
    public List<String> getList(String key, List<String> defaultValue) {
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

    /**
     * 按前缀读取 Integer Map。
     *
     * 适用场景：rpc.filter.order.{name}=100 这类动态 key 配置。
     * 边界处理：前缀后为空的 key 会被忽略。
     */
    public Map<String, Integer> getIntegerMapByPrefix(String prefix) {
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

    /**
     * 按前缀读取 String Map。
     *
     * 适用场景：降级默认值、方法级动态配置等 key 数量不固定的场景。
     */
    public Map<String, String> getStringMapByPrefix(String prefix) {
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
