package com.rpc.core.resilience.degrade;

import com.rpc.core.resilience.DegradationPolicy;

import java.util.Map;

/**
 * 降级策略工厂。
 *
 * 所处阶段：Bootstrap 根据配置初始化过滤器运行时降级策略时。
 * 主要职责：把配置字符串转换为具体 DegradationPolicy 实例，屏蔽策略选择细节。
 */
public final class DegradationPolicyFactory {
    /** 快速失败降级策略名。 */
    public static final String FAIL_FAST = "failFast";
    /** 默认值降级策略名。 */
    public static final String DEFAULT_VALUE = "defaultValue";

    /** 工厂类不允许实例化。 */
    private DegradationPolicyFactory() {
    }

    /**
     * 根据配置创建降级策略。
     *
     * 边界处理：策略名为空或未知时默认 failFast；defaultValues 为空时创建无默认值的 DefaultValueDegradation。
     */
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

    /**
     * 将配置文件中的字符串默认值解析为常见标量类型。
     *
     * 注意事项：配置文件只能提供字符串，这里提前解析 boolean、int、long、double，让降级返回值更贴近方法返回类型。
     */
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

