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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RPC 自定义 SPI 扩展加载器。
 *
 * 所处阶段：框架需要按名称获取序列化器、负载均衡器、过滤器等扩展实现时使用。
 * 主要职责：
 * - 从 META-INF/rpc/{接口全名} 加载扩展名到实现类的映射。
 * - 按扩展名懒加载并缓存单例实例。
 * - 支持 @Inject 依赖注入和 @Initialize 初始化方法。
 * - 使用线程内创建链路检测循环依赖，避免并发加载时误判。
 *
 * 注意事项：
 * - SPI 实现默认按单例复用，扩展类应尽量无状态或自行保证线程安全。
 * - 加载失败不能污染缓存，否则后续重试会一直拿到半初始化状态。
 */
@Slf4j
public class ExtensionLoader<T> {
    private static final String SPI_DIRECTORY = "META-INF/rpc/";
    private static final Map<Class<?>, ExtensionLoader<?>> LOADERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Set<Class<?>>> BUILDING_INSTANCES =
            ThreadLocal.withInitial(HashSet::new);

    private final Class<T> type;
    private final Map<String, Class<?>> extensionClasses = new ConcurrentHashMap<>();
    private final Map<String, T> singletonInstances = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    private ExtensionLoader(Class<T> type) {
        this.type = type;
    }

    /** 获取某个 SPI 接口对应的加载器；只有带 @SPI 的接口才允许进入扩展机制。 */
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

    /** 按扩展名获取实例；实例会懒加载并缓存在 singletonInstances 中。 */
    public T getExtension(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name == null");
        }
        loadExtensionClasses();
        return singletonInstances.computeIfAbsent(name, this::createExtension);
    }

    /** 获取 @SPI 注解上声明的默认扩展；未声明时回退到任意一个已加载扩展。 */
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

    /** 清理当前 SPI 类型的类映射和实例缓存，主要用于测试隔离或运行时重载。 */
    public synchronized void clearCache() {
        singletonInstances.clear();
        extensionClasses.clear();
        initialized = false;
    }

    /** 清理所有 SPI 加载器缓存；测试中避免不同用例之间共享扩展状态。 */
    public static synchronized void clearAllLoaders() {
        LOADERS.values().forEach(ExtensionLoader::clearCache);
        LOADERS.clear();
        BUILDING_INSTANCES.remove();
    }

    public boolean hasExtension(String name) {
        loadExtensionClasses();
        return extensionClasses.containsKey(name);
    }

    public Class<?> getExtensionClass(String name) {
        loadExtensionClasses();
        return extensionClasses.get(name);
    }

    /** 懒加载扩展类映射，读取 META-INF/rpc 下的 SPI 配置文件。 */
    private synchronized void loadExtensionClasses() {
        if (initialized) {
            return;
        }

        String fileName = SPI_DIRECTORY + type.getName();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Map<String, Class<?>> loadedExtensions = new ConcurrentHashMap<>();
        try {
            Enumeration<URL> urls = classLoader.getResources(fileName);
            while (urls.hasMoreElements()) {
                loadResource(urls.nextElement(), loadedExtensions);
            }
            extensionClasses.clear();
            extensionClasses.putAll(loadedExtensions);
            initialized = true;
        } catch (IOException e) {
            log.error("Failed to load SPI resource {}", fileName, e);
        }
    }

    /** 解析单个 SPI 资源文件，格式为 name=implClassName。 */
    private void loadResource(URL url, Map<String, Class<?>> loadedExtensions) {
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
                    Class<?> clazz = Class.forName(
                            className,
                            false,
                            Thread.currentThread().getContextClassLoader()
                    );
                    if (!type.isAssignableFrom(clazz)) {
                        log.warn("{} is not assignable to {}", className, type.getName());
                        continue;
                    }
                    loadedExtensions.put(name, clazz);
                    log.debug("Loaded SPI extension {} -> {}", name, className);
                } catch (ClassNotFoundException e) {
                    log.warn("SPI implementation class not found: {}", className);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read SPI resource {}", url, e);
        }
    }

    /**
     * 创建扩展实例。
     *
     * 创建链路记录在 ThreadLocal 中，只检测当前线程内真实创建链路，
     * 避免不同线程并发加载不同扩展时被全局集合误判为循环依赖。
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        Class<?> clazz = extensionClasses.get(name);
        if (clazz == null) {
            throw new IllegalStateException("No such extension: " + name);
        }

        Set<Class<?>> buildingInstances = BUILDING_INSTANCES.get();
        if (!buildingInstances.add(clazz)) {
            throw new IllegalStateException("Circular dependency detected while creating " + clazz.getName());
        }

        try {
            T instance = (T) clazz.getDeclaredConstructor().newInstance();
            injectExtension(instance);
            invokeInitializeMethod(instance);
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create extension: " + name, e);
        } finally {
            buildingInstances.remove(clazz);
            if (buildingInstances.isEmpty()) {
                BUILDING_INSTANCES.remove();
            }
        }
    }

    /** 对扩展实例中标记 @Inject 的字段做依赖注入。 */
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

    /** 注入单个字段；required=false 时找不到依赖会跳过，不影响扩展创建。 */
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
                            "No dependency found for field " + field.getName() + " of type " + fieldType.getName()
                    );
                }
                return;
            }

            field.setAccessible(true);
            field.set(instance, dependency);
            log.debug(
                    "Injected {}#{} with {}",
                    instance.getClass().getSimpleName(),
                    field.getName(),
                    dependency.getClass().getSimpleName()
            );
        } catch (IllegalAccessException e) {
            if (required) {
                throw new RuntimeException("Failed to inject field " + field.getName(), e);
            }
            log.warn(
                    "Failed to inject optional field {}#{}",
                    instance.getClass().getSimpleName(),
                    field.getName(),
                    e
            );
        }
    }

    /** 解析字段依赖；如果依赖类型本身是 SPI，则继续走扩展加载器。 */
    private Object resolveDependency(Class<?> fieldType, String injectName) {
        if (fieldType.isAnnotationPresent(SPI.class)) {
            ExtensionLoader<?> loader = getExtensionLoader(fieldType);
            if (injectName != null && !injectName.isBlank()) {
                if (!loader.hasExtension(injectName)) {
                    return null;
                }
                return loader.getExtension(injectName);
            }
            return loader.getDefaultExtension();
        }
        return getBeanFromContainer(fieldType, injectName);
    }

    /** 预留外部容器注入入口；当前 core 层不直接依赖 Spring 容器。 */
    private Object getBeanFromContainer(Class<?> beanType, String name) {
        return null;
    }

    /** 调用扩展实例上的 @Initialize 方法，用于完成扩展自己的初始化逻辑。 */
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
                log.warn(
                        "Failed to invoke initialize method {}#{}",
                        instance.getClass().getSimpleName(),
                        method.getName(),
                        e
                );
            }
        }
    }
}
