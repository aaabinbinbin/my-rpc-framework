package com.rpc.spi;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扩展加载器
 * 
 * 负责加载指定接口的所有实现类，并支持依赖注入
 * 
 * @param <T> 扩展点接口类型
 */
@Slf4j
public class ExtensionLoader<T> {
    
    private static final String SPI_DIRECTORY = "META-INF/rpc/";
    
    private static final Map<Class<?>, ExtensionLoader<?>> LOADERS = new ConcurrentHashMap<>();
    
    private static final Set<Class<?>> BUILDING_INSTANCES = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
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
     * 创建扩展实例（支持依赖注入）
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        Class<?> clazz = extensionClasses.get(name);
        if (clazz == null) {
            throw new IllegalStateException("找不到扩展: " + name);
        }
        
        try {
            // 检查循环依赖
            if (BUILDING_INSTANCES.contains(clazz)) {
                throw new IllegalStateException("检测到循环依赖: " + clazz.getName());
            }
            
            BUILDING_INSTANCES.add(clazz);
            
            // 创建实例
            T instance = (T) clazz.getDeclaredConstructor().newInstance();
            
            // 注入依赖
            injectExtension(instance);
            
            // 调用初始化方法
            invokeInitializeMethod(instance);
            
            return instance;
            
        } catch (Exception e) {
            log.error("创建扩展实例失败: {}", name, e);
            throw new RuntimeException("创建扩展实例失败: " + name, e);
        } finally {
            BUILDING_INSTANCES.remove(clazz);
        }
    }
    
    /**
     * 注入依赖
     */
    private void injectExtension(Object instance) {
        Class<?> clazz = instance.getClass();
        
        // 遍历所有字段（包括父类）
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(Inject.class)) {
                    injectField(instance, field);
                }
            }
            clazz = clazz.getSuperclass();
        }
    }
    
    /**
     * 注入字段
     */
    private void injectField(Object instance, Field field) {
        Inject inject = field.getAnnotation(Inject.class);
        Class<?> fieldType = field.getType();
        String injectName = inject.value();
        boolean required = inject.required();
        
        try {
            Object dependency = null;
            
            // 如果字段类型是 SPI 扩展点，使用 ExtensionLoader 加载
            if (fieldType.isAnnotationPresent(SPI.class)) {
                ExtensionLoader<?> loader = getExtensionLoader(fieldType);
                
                if (injectName != null && !injectName.isEmpty()) {
                    // 先检查扩展是否存在，避免 getExtension 抛出异常
                    if (loader.hasExtension(injectName)) {
                        dependency = loader.getExtension(injectName);
                    } else {
                        // 扩展不存在
                        if (required) {
                            throw new IllegalStateException("找不到扩展: " + injectName + 
                                " (field: " + field.getName() + ")");
                        } else {
                            log.debug("可选扩展不存在，跳过注入: {} -> {} (扩展名: {})", 
                                instance.getClass().getSimpleName(), field.getName(), injectName);
                            return;
                        }
                    }
                } else {
                    dependency = loader.getDefaultExtension();
                }
            } else {
                // 非 SPI 扩展点，尝试从全局容器获取（可扩展）
                dependency = getBeanFromContainer(fieldType, injectName);
            }
            
            if (dependency == null) {
                if (required) {
                    throw new IllegalStateException("无法注入依赖: " + field.getName() + 
                        " (type: " + fieldType.getName() + ")");
                } else {
                    log.debug("可选依赖未找到，跳过注入: {} -> {}", 
                        instance.getClass().getSimpleName(), field.getName());
                    return;
                }
            }
            
            // 设置字段值
            field.setAccessible(true);
            field.set(instance, dependency);
            
            log.debug("注入依赖成功: {}#{} = {}", 
                instance.getClass().getSimpleName(), 
                field.getName(), 
                dependency.getClass().getSimpleName());
            
        } catch (IllegalAccessException e) {
            if (required) {
                throw new RuntimeException("注入依赖失败: " + field.getName(), e);
            } else {
                log.warn("注入依赖失败（非必须）: {} -> {}", 
                    instance.getClass().getSimpleName(), field.getName(), e);
            }
        } catch (IllegalStateException e) {
            // 捕获 getExtension 可能抛出的其他 IllegalStateException
            if (required) {
                throw e;
            } else {
                log.debug("可选依赖加载失败，跳过注入: {} -> {} ({})", 
                    instance.getClass().getSimpleName(), field.getName(), e.getMessage());
            }
        }
    }
    
    /**
     * 从容器获取 Bean（可扩展为 Spring 等容器）
     */
    private Object getBeanFromContainer(Class<?> type, String name) {
        return null;
    }
    
    /**
     * 调用初始化方法
     */
    private void invokeInitializeMethod(Object instance) {
        Class<?> clazz = instance.getClass();
        
        // 查找 @Initialize 注解的方法
        for (Method method : clazz.getMethods()) {
            if (method.isAnnotationPresent(Initialize.class)) {
                try {
                    method.setAccessible(true);
                    method.invoke(instance);
                    log.debug("调用初始化方法: {}#{}", 
                        clazz.getSimpleName(), method.getName());
                } catch (Exception e) {
                    log.warn("调用初始化方法失败: {}#{}", 
                        clazz.getSimpleName(), method.getName(), e);
                }
            }
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