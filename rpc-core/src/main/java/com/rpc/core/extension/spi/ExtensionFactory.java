package com.rpc.core.extension.spi;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPI 扩展工厂门面。
 *
 * 上层代码通常不直接操作 ExtensionLoader，
 * 而是通过这个工厂按“扩展类型 + 名称”获取实现对象。
 *
 * 这样框架各处的调用方式会更统一：
 * - 获取默认扩展
 * - 按名称获取扩展
 * - 获取所有支持的扩展名
 */
@Slf4j
public class ExtensionFactory {
    /** 默认扩展缓存。绝大多数默认扩展是无状态单例，适合全局复用。 */
    private static final Map<Class<?>, Object> DEFAULT_EXTENSIONS = new ConcurrentHashMap<>();

    /**
     * 获取某种扩展类型的默认实现。
     *
     * 默认实现会被缓存下来，避免每次都重新通过 ExtensionLoader 解析。
     */
    @SuppressWarnings("unchecked")
    public static <T> T getDefaultExtension(Class<T> type) {
        return (T) DEFAULT_EXTENSIONS.computeIfAbsent(type, t -> {
            ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
            return loader.getDefaultExtension();
        });
    }

    /** 按扩展名获取具体实现。 */
    public static <T> T getExtension(Class<T> type, String name) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.getExtension(name);
    }

    /** 获取某种扩展类型下的全部实现对象。 */
    public static <T> List<T> getExtensions(Class<T> type) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.getExtensions();
    }

    /** 获取某种扩展类型支持的所有扩展名。 */
    public static <T> Set<String> getSupportedExtensions(Class<T> type) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.getSupportedExtensions();
    }

    /** 判断某个扩展名是否存在。 */
    public static <T> boolean hasExtension(Class<T> type, String name) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.hasExtension(name);
    }
}
