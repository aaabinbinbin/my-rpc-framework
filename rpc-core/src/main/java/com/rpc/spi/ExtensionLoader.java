package com.rpc.spi;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扩展加载器
 * 
 * 负责加载指定接口的所有实现类
 * 
 * @param <T> 扩展点接口类型
 */
@Slf4j
public class ExtensionLoader<T> {
    
    private static final String SPI_DIRECTORY = "META-INF/rpc/";
    
    private static final Map<Class<?>, ExtensionLoader<?>> LOADERS = new ConcurrentHashMap<>();
    
    private final Class<T> type;
    private final Map<String, Class<?>> extensionClasses = new ConcurrentHashMap<>();
    private final Map<String, T> singletonInstances = new ConcurrentHashMap<>();
    private final Map<String, String> classNames = new ConcurrentHashMap<>();
    
    private volatile boolean initialized = false;
    
    private ExtensionLoader(Class<T> type) {
        this.type = type;
    }
    
    /**
     * 获取扩展加载器
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("type == null");
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("type must be interface");
        }
        if (!type.isAnnotationPresent(SPI.class)) {
            throw new IllegalArgumentException("type must be annotated with @SPI");
        }
        
        return (ExtensionLoader<T>) LOADERS.computeIfAbsent(type, ExtensionLoader::new);
    }
    
    /**
     * 根据名称获取扩展实例
     */
    public T getExtension(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name == null");
        }
        
        loadExtensionClasses();
        
        return singletonInstances.computeIfAbsent(name, this::createExtension);
    }
    
    /**
     * 获取默认扩展实例
     */
    public T getDefaultExtension() {
        loadExtensionClasses();
        
        SPI spi = type.getAnnotation(SPI.class);
        String defaultName = spi.value();
        
        if (defaultName == null || defaultName.isEmpty()) {
            if (extensionClasses.isEmpty()) {
                return null;
            }
            return getExtension(extensionClasses.keySet().iterator().next());
        }
        
        return getExtension(defaultName);
    }
    
    /**
     * 获取所有扩展实例
     */
    public List<T> getExtensions() {
        loadExtensionClasses();
        
        List<T> extensions = new ArrayList<>();
        for (String name : extensionClasses.keySet()) {
            extensions.add(getExtension(name));
        }
        return extensions;
    }
    
    /**
     * 获取所有扩展名称
     */
    public Set<String> getSupportedExtensions() {
        loadExtensionClasses();
        return Collections.unmodifiableSet(extensionClasses.keySet());
    }
    
    /**
     * 加载所有扩展类
     */
    private synchronized void loadExtensionClasses() {
        if (initialized) {
            return;
        }
        
        initialized = true;
        
        String fileName = SPI_DIRECTORY + type.getName();
        
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        
        try {
            Enumeration<URL> urls = classLoader.getResources(fileName);
            
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                loadResources(url);
            }
            
        } catch (IOException e) {
            log.error("加载扩展配置文件失败: {}", fileName, e);
        }
    }
    
    /**
     * 加载配置文件
     */
    private void loadResources(URL url) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream()))) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                int i = line.indexOf('=');
                if (i > 0) {
                    String name = line.substring(0, i).trim();
                    String className = line.substring(i + 1).trim();
                    
                    if (!name.isEmpty() && !className.isEmpty()) {
                        try {
                            Class<?> clazz = Class.forName(className, false, 
                                Thread.currentThread().getContextClassLoader());
                            
                            if (!type.isAssignableFrom(clazz)) {
                                log.warn("{} 不是 {} 的实现类", className, type.getName());
                                continue;
                            }
                            
                            extensionClasses.put(name, clazz);
                            classNames.put(name, className);
                            
                            log.debug("加载扩展: {} = {}", name, className);
                            
                        } catch (ClassNotFoundException e) {
                            log.warn("找不到扩展类: {}", className);
                        }
                    }
                }
            }
            
        } catch (IOException e) {
            log.error("读取配置文件失败: {}", url, e);
        }
    }
    
    /**
     * 创建扩展实例
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        Class<?> clazz = extensionClasses.get(name);
        if (clazz == null) {
            throw new IllegalStateException("找不到扩展: " + name);
        }
        
        try {
            T instance = (T) clazz.getDeclaredConstructor().newInstance();
            return instance;
            
        } catch (Exception e) {
            log.error("创建扩展实例失败: {}", name, e);
            throw new RuntimeException("创建扩展实例失败: " + name, e);
        }
    }
    
    /**
     * 检查是否有指定名称的扩展
     */
    public boolean hasExtension(String name) {
        loadExtensionClasses();
        return extensionClasses.containsKey(name);
    }
}