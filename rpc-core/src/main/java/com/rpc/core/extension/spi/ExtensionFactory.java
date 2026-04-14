package com.rpc.core.extension.spi;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPI 扩展门面。
 */
@Slf4j
public class ExtensionFactory {
    private static final Map<Class<?>, Object> DEFAULT_EXTENSIONS = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public static <T> T getDefaultExtension(Class<T> type) {
        return (T) DEFAULT_EXTENSIONS.computeIfAbsent(type, t -> {
            ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
            return loader.getDefaultExtension();
        });
    }

    public static <T> T getExtension(Class<T> type, String name) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        String resolvedName = resolveName(loader, name);
        return loader.getExtension(resolvedName);
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
        return loader.hasExtension(name) || loader.hasExtension(normalize(name));
    }

    private static <T> String resolveName(ExtensionLoader<T> loader, String name) {
        if (loader.hasExtension(name)) {
            return name;
        }
        String normalized = normalize(name);
        return loader.hasExtension(normalized) ? normalized : name;
    }

    private static String normalize(String name) {
        return name == null ? null : name.trim().toLowerCase();
    }

    public static void clearCache() {
        DEFAULT_EXTENSIONS.clear();
        ExtensionLoader.clearAllLoaders();
    }
}
