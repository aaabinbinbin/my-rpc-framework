package com.rpc.core.extension.spi;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 框架内部使用的轻量级 SPI（可插拔扩展点）加载器。
 */
@Slf4j
public class ExtensionLoader<T> {
    private static final String SPI_DIRECTORY = "META-INF/rpc/";
    private static final Map<Class<?>, ExtensionLoader<?>> LOADERS = new ConcurrentHashMap<>();
    /**
     * 记录当前正在构造的 SPI（可插拔扩展点）实现，便于尽早发现
     * A -> B -> A 这类循环依赖。
     */
    private static final Set<Class<?>> BUILDING_INSTANCES =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final Class<T> type;
    private final Map<String, Class<?>> extensionClasses = new ConcurrentHashMap<>();
    private final Map<String, T> singletonInstances = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    private ExtensionLoader(Class<T> type) {
        this.type = type;
    }

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

    public T getExtension(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name == null");
        }
        loadExtensionClasses();
        // 扩展实例按名称缓存，避免同一个实现类在运行过程中被重复创建。
        return singletonInstances.computeIfAbsent(name, this::createExtension);
    }

    public T getDefaultExtension() {
        loadExtensionClasses();
        SPI spi = type.getAnnotation(SPI.class);
        String defaultName = spi.value();
        if (defaultName == null || defaultName.isBlank()) {
            if (extensionClasses.isEmpty()) {
                return null;
            }
            return getExtension(extensionClasses.keySet().iterator().next());
        }
        return getExtension(defaultName);
    }

    public List<T> getExtensions() {
        loadExtensionClasses();
        List<T> result = new ArrayList<>();
        for (String name : extensionClasses.keySet()) {
            result.add(getExtension(name));
        }
        return result;
    }

    public Set<String> getSupportedExtensions() {
        loadExtensionClasses();
        return Collections.unmodifiableSet(extensionClasses.keySet());
    }

    public boolean hasExtension(String name) {
        loadExtensionClasses();
        return extensionClasses.containsKey(name);
    }

    public Class<?> getExtensionClass(String name) {
        loadExtensionClasses();
        return extensionClasses.get(name);
    }

    private synchronized void loadExtensionClasses() {
        if (initialized) {
            return;
        }
        initialized = true;

        // 每个 SPI（可插拔扩展点）接口都对应 META-INF/rpc/ 下的一个资源文件，
        // 文件内容格式为：name=implementationClass。
        String fileName = SPI_DIRECTORY + type.getName();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            Enumeration<URL> urls = classLoader.getResources(fileName);
            while (urls.hasMoreElements()) {
                loadResource(urls.nextElement());
            }
        } catch (IOException e) {
            log.error("Failed to load SPI resource {}", fileName, e);
        }
    }

    private void loadResource(URL url) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int index = line.indexOf('=');
                if (index <= 0) {
                    continue;
                }

                String name = line.substring(0, index).trim();
                String className = line.substring(index + 1).trim();
                if (name.isEmpty() || className.isEmpty()) {
                    continue;
                }

                try {
                    Class<?> clazz = Class.forName(className, false,
                            Thread.currentThread().getContextClassLoader());
                    if (!type.isAssignableFrom(clazz)) {
                        log.warn("{} is not assignable to {}", className, type.getName());
                        continue;
                    }
                    extensionClasses.put(name, clazz);
                    log.debug("Loaded SPI extension {} -> {}", name, className);
                } catch (ClassNotFoundException e) {
                    log.warn("SPI implementation class not found: {}", className);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read SPI resource {}", url, e);
        }
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        Class<?> clazz = extensionClasses.get(name);
        if (clazz == null) {
            throw new IllegalStateException("No such extension: " + name);
        }

        if (!BUILDING_INSTANCES.add(clazz)) {
            throw new IllegalStateException("Circular dependency detected while creating " + clazz.getName());
        }

        try {
            T instance = (T) clazz.getDeclaredConstructor().newInstance();
            // 扩展实例的生命周期在这里统一处理：
            // 1. 创建实例
            // 2. 执行 @Inject（注入）依赖注入
            // 3. 调用 @Initialize（初始化）方法
            injectExtension(instance);
            invokeInitializeMethod(instance);
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create extension: " + name, e);
        } finally {
            BUILDING_INSTANCES.remove(clazz);
        }
    }

    private void injectExtension(Object instance) {
        Class<?> current = instance.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Inject.class)) {
                    injectField(instance, field);
                }
            }
            current = current.getSuperclass();
        }
    }

    private void injectField(Object instance, Field field) {
        Inject inject = field.getAnnotation(Inject.class);
        Class<?> fieldType = field.getType();
        String injectName = inject.value();
        boolean required = inject.required();

        try {
            Object dependency = resolveDependency(fieldType, injectName);
            if (dependency == null) {
                if (required) {
                    throw new IllegalStateException(
                            "No dependency found for field " + field.getName() + " of type " + fieldType.getName());
                }
                return;
            }

            field.setAccessible(true);
            field.set(instance, dependency);
            log.debug("Injected {}#{} with {}", instance.getClass().getSimpleName(),
                    field.getName(), dependency.getClass().getSimpleName());
        } catch (IllegalAccessException e) {
            if (required) {
                throw new RuntimeException("Failed to inject field " + field.getName(), e);
            }
            log.warn("Failed to inject optional field {}#{}", instance.getClass().getSimpleName(), field.getName(), e);
        }
    }

    private Object resolveDependency(Class<?> fieldType, String injectName) {
        if (fieldType.isAnnotationPresent(SPI.class)) {
            ExtensionLoader<?> loader = getExtensionLoader(fieldType);
            if (injectName != null && !injectName.isBlank()) {
                // 命名注入适用于一个 SPI（可插拔扩展点）下有多个实现，
                // 且依赖方需要显式指定目标实现。
                if (!loader.hasExtension(injectName)) {
                    return null;
                }
                return loader.getExtension(injectName);
            }
            // 如果没有显式指定名称，则回退到该 SPI（可插拔扩展点）的默认实现。
            return loader.getDefaultExtension();
        }
        return getBeanFromContainer(fieldType, injectName);
    }

    private Object getBeanFromContainer(Class<?> beanType, String name) {
        return null;
    }

    private void invokeInitializeMethod(Object instance) {
        for (Method method : instance.getClass().getMethods()) {
            if (!method.isAnnotationPresent(Initialize.class)) {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(instance);
                log.debug("Initialized {}#{}", instance.getClass().getSimpleName(), method.getName());
            } catch (Exception e) {
                log.warn("Failed to invoke initialize method {}#{}",
                        instance.getClass().getSimpleName(), method.getName(), e);
            }
        }
    }
}
