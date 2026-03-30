package com.rpc.core.extension.spi;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ExtensionLoader}（扩展加载器）的门面。
 * 上层通过它完成扩展查询，而不直接处理 loader（加载器）的生命周期，
 * 这样大多数扩展查找都能保持成简单的“按类型/名称获取”操作。
 */
@Slf4j
public class ExtensionFactory {
    private static final Map<Class<?>, Object> DEFAULT_EXTENSIONS = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public static <T> T getDefaultExtension(Class<T> type) {
    // 默认实现按 SPI（可插拔扩展点）类型缓存，
    // 因为它们通常是无状态单例，会在多条请求路径里被重复使用。
        return (T) DEFAULT_EXTENSIONS.computeIfAbsent(type, t -> {
            ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
            return loader.getDefaultExtension();
        });
    }

    public static <T> T getExtension(Class<T> type, String name) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.getExtension(name);
    }

    public static <T> List<T> getExtensions(Class<T> type) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.getExtensions();
    }

    public static <T> Set<String> getSupportedExtensions(Class<T> type) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.getSupportedExtensions();
    }

    public static <T> boolean hasExtension(Class<T> type, String name) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.hasExtension(name);
    }
}
