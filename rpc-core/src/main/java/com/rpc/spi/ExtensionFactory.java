package com.rpc.spi;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扩展工厂
 * 
 * 提供统一的扩展获取入口
 */
@Slf4j
public class ExtensionFactory {
    
    private static final Map<Class<?>, Object> DEFAULT_EXTENSIONS = new ConcurrentHashMap<>();
    
    /**
     * 获取默认扩展实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T getDefaultExtension(Class<T> type) {
        return (T) DEFAULT_EXTENSIONS.computeIfAbsent(type, t -> {
            ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
            return loader.getDefaultExtension();
        });
    }
    
    /**
     * 根据名称获取扩展实例
     */
    public static <T> T getExtension(Class<T> type, String name) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.getExtension(name);
    }
    
    /**
     * 获取所有扩展实例
     */
    public static <T> List<T> getExtensions(Class<T> type) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.getExtensions();
    }
    
    /**
     * 获取支持的扩展名称
     */
    public static <T> Set<String> getSupportedExtensions(Class<T> type) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.getSupportedExtensions();
    }
    
    /**
     * 检查是否有指定名称的扩展
     */
    public static <T> boolean hasExtension(Class<T> type, String name) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.hasExtension(name);
    }
}