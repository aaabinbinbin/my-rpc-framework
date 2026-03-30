package com.rpc.core.resilience.degrade;

import com.rpc.core.resilience.DegradationPolicy;

import java.util.Map;

public final class DegradationPolicyFactory {
    public static final String FAIL_FAST = "failFast";
    public static final String DEFAULT_VALUE = "defaultValue";

    private DegradationPolicyFactory() {
    }

    public static DegradationPolicy create(String policyName, Map<String, String> defaultValues) {
    // 策略创建统一留在工厂里，
    // 这样启动层只需要传配置，不用自己编码策略选择逻辑。
        String normalized = policyName == null || policyName.isBlank() ? FAIL_FAST : policyName.trim();
        if (DEFAULT_VALUE.equalsIgnoreCase(normalized)) {
            DefaultValueDegradation degradation = new DefaultValueDegradation();
            if (defaultValues != null) {
                defaultValues.forEach((key, value) -> degradation.setDefaultValue(key, parseValue(value)));
            }
            return degradation;
        }
        return new FailFastDegradation();
    }

    private static Object parseValue(String value) {
        if (value == null) {
            return null;
        }
    // 配置文件只能提供字符串，
    // 这里把常见标量类型提前解析掉，让降级默认值更自然地匹配 RPC 返回类型。
        String trimmed = value.trim();
        if ("null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {
        }
        return trimmed;
    }
}

