# 第 11 课：SPI 机制与可扩展性

## 学习目标

- 理解 SPI（Service Provider Interface）机制原理
- 掌握 Java 原生 SPI 的使用
- 实现自定义 SPI 加载器
- 设计可插拔的扩展机制
- 实现序列化器和负载均衡器的动态扩展

---

## 一、什么是 SPI？

### 1.1 SPI 简介

**SPI（Service Provider Interface）** 是 Java 提供的一种服务发现机制，允许在运行时动态加载接口的实现类，实现接口与实现的解耦。

```
┌─────────────────────────────────────────────────────────┐
│                    SPI 核心思想                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│   【传统方式】                                          │
│                                                         │
│     接口定义                    实现类                  │
│    ┌────────┐                 ┌────────┐               │
│    │  接口  │ <────────────── │ 实现类 │               │
│    └────────┘    直接依赖      └────────┘               │
│                                                         │
│   问题：耦合度高，更换实现需要修改代码                  │
│                                                         │
├─────────────────────────────────────────────────────────┤
│                                                         │
│   【SPI 方式】                                          │
│                                                         │
│     接口定义                   配置文件                 │
│    ┌────────┐               ┌──────────┐               │
│    │  接口  │ <──────────── │ META-INF │               │
│    └────────┘   运行时加载   │services/ │               │
│         ↑                     └──────────┘               │
│         │                          │                     │
│    ┌────┴────┐                     │                     │
│    │         │                     ↓                     │
│ ┌──────┐ ┌──────┐            ┌──────────┐               │
│ │实现A │ │实现B │            │ 全限定名  │               │
│ └──────┘ └──────┘            └──────────┘               │
│                                                         │
│   优势：解耦，可插拔，运行时动态替换                    │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 1.2 SPI vs API

| 特性 | API（Application Programming Interface） | SPI（Service Provider Interface） |
|------|------------------------------------------|-----------------------------------|
| **定义** | 应用编程接口 | 服务提供者接口 |
| **方向** | 接口 → 实现（正向） | 接口 ← 实现（反向） |
| **控制权** | 开发者控制 | 框架控制 |
| **扩展性** | 需要修改代码 | 无需修改代码 |
| **典型应用** | JDBC、SLF4J、Spring | Dubbo、Spring Boot |

### 1.3 SPI 在 RPC 框架中的应用

```
┌─────────────────────────────────────────────────────────┐
│               RPC 框架中的 SPI 应用场景                 │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  1. 序列化器扩展                                        │
│     ┌──────────┐                                       │
│     │Serializer│ ← KryoSerializer / HessianSerializer  │
│     └──────────┘   / JsonSerializer / ProtobufSerializer│
│                                                         │
│  2. 负载均衡策略扩展                                    │
│     ┌───────────┐                                      │
│     │LoadBalance│ ← RandomLoadBalancer / RoundRobin    │
│     └───────────┘   / ConsistentHash / LeastConnection │
│                                                         │
│  3. 注册中心扩展                                        │
│     ┌──────────────┐                                   │
│     │ServiceRegistry│ ← ZookeeperRegistry / Nacos      │
│     └──────────────┘   / ConsulRegistry / Redis        │
│                                                         │
│  4. 网络传输扩展                                        │
│     ┌──────────┐                                       │
│     │Transport │ ← NettyTransport / MinaTransport      │
│     └──────────┘   / GrizzlyTransport                  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 二、Java 原生 SPI 机制

### 2.1 Java SPI 使用步骤

**步骤 1：定义接口**

```java
package com.rpc.serialize;

/**
 * 序列化接口
 */
public interface Serializer {
    
    /**
     * 序列化
     */
    byte[] serialize(Object obj) throws IOException;
    
    /**
     * 反序列化
     */
    <T> T deserialize(byte[] bytes, Class<T> clazz) throws IOException;
    
    /**
     * 获取序列化器类型
     */
    byte getSerializerType();
}
```

**步骤 2：创建实现类**

```java
package com.rpc.serialize.impl;

public class KryoSerializer implements Serializer {
    @Override
    public byte[] serialize(Object obj) throws IOException {
        // Kryo 序列化实现
    }
    
    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) throws IOException {
        // Kryo 反序列化实现
    }
    
    @Override
    public byte getSerializerType() {
        return 1;
    }
}

public class JsonSerializer implements Serializer {
    @Override
    public byte[] serialize(Object obj) throws IOException {
        // JSON 序列化实现
    }
    
    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) throws IOException {
        // JSON 反序列化实现
    }
    
    @Override
    public byte getSerializerType() {
        return 2;
    }
}
```

**步骤 3：创建配置文件**

在 `resources/META-INF/services/` 目录下创建文件，文件名为接口全限定名：

```
resources/
└── META-INF/
    └── services/
        └── com.rpc.serialize.Serializer
```

文件内容为实现类全限定名：

```
com.rpc.serialize.impl.KryoSerializer
com.rpc.serialize.impl.JsonSerializer
com.rpc.serialize.impl.HessianSerializer
```

**步骤 4：使用 ServiceLoader 加载**

```java
import java.util.ServiceLoader;

public class SpiDemo {
    public static void main(String[] args) {
        // 加载所有 Serializer 实现
        ServiceLoader<Serializer> loaders = ServiceLoader.load(Serializer.class);
        
        // 遍历所有实现
        for (Serializer serializer : loaders) {
            System.out.println("找到实现：" + serializer.getClass().getName());
            System.out.println("类型：" + serializer.getSerializerType());
        }
    }
}
```

### 2.2 Java SPI 原理分析

```
┌─────────────────────────────────────────────────────────┐
│                ServiceLoader 工作原理                   │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ServiceLoader.load(Serializer.class)                  │
│           │                                             │
│           ↓                                             │
│  1. 获取类加载器                                        │
│     ClassLoader loader = Thread.currentThread()         │
│                         .getContextClassLoader()        │
│           │                                             │
│           ↓                                             │
│  2. 构建配置文件路径                                    │
│     String fullName = "META-INF/services/"              │
│                     + Serializer.class.getName()        │
│           │                                             │
│           ↓                                             │
│  3. 读取配置文件                                        │
│     Enumeration<URL> configs = loader.getResources()    │
│           │                                             │
│           ↓                                             │
│  4. 解析配置文件内容                                    │
│     while (reader.hasNext()) {                          │
│         String className = reader.readLine();           │
│         // 加载类                                       │
│         Class<?> clazz = Class.forName(className);      │
│         // 实例化                                       │
│         Object instance = clazz.newInstance();          │
│     }                                                   │
│           │                                             │
│           ↓                                             │
│  5. 返回迭代器                                          │
│     return new LazyIterator(providers);                 │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 2.3 Java SPI 的优缺点

**优点：**

✅ 解耦：接口与实现分离  
✅ 可插拔：无需修改代码即可更换实现  
✅ 扩展性强：新增实现只需添加配置文件  
✅ 框架友好：适合框架设计

**缺点：**

❌ 延迟加载：每次遍历都会重新加载  
❌ 无缓存：重复加载浪费资源  
❌ 无优先级：无法指定默认实现  
❌ 无类型安全：配置错误只能在运行时发现  
❌ 无依赖注入：无法注入其他组件

---

## 三、实现自定义 SPI 加载器

为了解决 Java SPI 的不足，我们实现一个增强版 SPI 加载器。

### 3.1 整体设计

```
┌─────────────────────────────────────────────────────────┐
│                  自定义 SPI 架构设计                     │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │              ExtensionLoader<T>                  │   │
│  │  （扩展加载器，每个接口一个实例）                 │   │
│  └─────────────────────────────────────────────────┘   │
│                         │                               │
│           ┌─────────────┼─────────────┐                │
│           ↓             ↓             ↓                │
│    ┌──────────┐  ┌──────────┐  ┌──────────┐           │
│    │缓存管理  │  │配置解析  │  │实例创建  │           │
│    └──────────┘  └──────────┘  └──────────┘           │
│                                                         │
│  核心特性：                                             │
│   1. 单例缓存：避免重复创建实例                        │
│   2. 懒加载：首次使用时才加载                          │
│   3. 优先级支持：支持 @SPI 注解指定默认实现            │
│   4. 类型安全：启动时验证配置                          │
│   5. 依赖注入：支持 @Inject 注入其他扩展               │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 3.2 核心接口设计

#### 3.2.1 @SPI 注解

```java
package com.rpc.spi;

import java.lang.annotation.*;

/**
 * SPI 扩展点注解
 * 标注在接口上，表示该接口是一个扩展点
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SPI {
    
    /**
     * 默认实现名称
     */
    String value() default "";
}
```

#### 3.2.2 ExtensionLoader 核心实现

```java
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
        
        // 确保已初始化
        loadExtensionClasses();
        
        // 从单例缓存获取
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
            // 如果没有指定默认值，返回第一个
            return extensionClasses.isEmpty() ? null : 
                getExtension(extensionClasses.keySet().iterator().next());
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
        
        // 从配置文件加载
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
                
                // 忽略空行和注释
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                // 解析格式：name=className
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
```

### 3.3 ExtensionFactory 工厂类

```java
package com.rpc.spi;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
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
    public static <T> java.util.List<T> getExtensions(Class<T> type) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.getExtensions();
    }
    
    /**
     * 获取支持的扩展名称
     */
    public static <T> java.util.Set<String> getSupportedExtensions(Class<T> type) {
        ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
        return loader.getSupportedExtensions();
    }
}
```

---

## 四、应用 SPI 扩展序列化器

### 4.1 定义序列化接口

```java
package com.rpc.serialize;

import com.rpc.spi.SPI;

/**
 * 序列化接口
 */
@SPI("kryo")
public interface Serializer {
    
    /**
     * 序列化
     */
    byte[] serialize(Object obj);
    
    /**
     * 反序列化
     */
    <T> T deserialize(byte[] bytes, Class<T> clazz);
    
    /**
     * 获取序列化器类型
     */
    byte getSerializerType();
    
    /**
     * 序列化器类型常量
     */
    byte KRYO = 1;
    byte JSON = 2;
    byte HESSIAN = 3;
    byte JAVA = 4;
}
```

### 4.2 实现各种序列化器

#### KryoSerializer

```java
package com.rpc.serialize.impl;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.rpc.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Kryo 序列化器
 */
@Slf4j
public class KryoSerializer implements Serializer {
    
    private static final ThreadLocal<Kryo> KRYO_LOCAL = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        kryo.setReferences(true);
        kryo.setRegistrationRequired(false);
        return kryo;
    });
    
    @Override
    public byte[] serialize(Object obj) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             Output output = new Output(baos)) {
            
            Kryo kryo = KRYO_LOCAL.get();
            kryo.writeClassAndObject(output, obj);
            output.flush();
            return baos.toByteArray();
            
        } catch (Exception e) {
            log.error("Kryo 序列化失败", e);
            throw new RuntimeException("Kryo 序列化失败", e);
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             Input input = new Input(bais)) {
            
            Kryo kryo = KRYO_LOCAL.get();
            Object obj = kryo.readClassAndObject(input);
            return (T) obj;
            
        } catch (Exception e) {
            log.error("Kryo 反序列化失败", e);
            throw new RuntimeException("Kryo 反序列化失败", e);
        }
    }
    
    @Override
    public byte getSerializerType() {
        return KRYO;
    }
}
```

#### JsonSerializer

```java
package com.rpc.serialize.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpc.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON 序列化器
 */
@Slf4j
public class JsonSerializer implements Serializer {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    @Override
    public byte[] serialize(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }
    
    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(bytes, clazz);
        } catch (Exception e) {
            log.error("JSON 反序列化失败", e);
            throw new RuntimeException("JSON 反序列化失败", e);
        }
    }
    
    @Override
    public byte getSerializerType() {
        return JSON;
    }
}
```

#### HessianSerializer

```java
package com.rpc.serialize.impl;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.rpc.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Hessian 序列化器
 */
@Slf4j
public class HessianSerializer implements Serializer {
    
    @Override
    public byte[] serialize(Object obj) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            HessianOutput output = new HessianOutput(baos);
            output.writeObject(obj);
            output.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Hessian 序列化失败", e);
            throw new RuntimeException("Hessian 序列化失败", e);
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            HessianInput input = new HessianInput(bais);
            return (T) input.readObject();
        } catch (Exception e) {
            log.error("Hessian 反序列化失败", e);
            throw new RuntimeException("Hessian 反序列化失败", e);
        }
    }
    
    @Override
    public byte getSerializerType() {
        return HESSIAN;
    }
}
```

#### JavaSerializer

```java
package com.rpc.serialize.impl;

import com.rpc.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;

import java.io.*;

/**
 * Java 原生序列化器
 */
@Slf4j
public class JavaSerializer implements Serializer {
    
    @Override
    public byte[] serialize(Object obj) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            
            oos.writeObject(obj);
            oos.flush();
            return baos.toByteArray();
            
        } catch (Exception e) {
            log.error("Java 序列化失败", e);
            throw new RuntimeException("Java 序列化失败", e);
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            
            return (T) ois.readObject();
            
        } catch (Exception e) {
            log.error("Java 反序列化失败", e);
            throw new RuntimeException("Java 反序列化失败", e);
        }
    }
    
    @Override
    public byte getSerializerType() {
        return JAVA;
    }
}
```

### 4.3 配置 SPI 扩展

在 `resources/META-INF/rpc/` 目录下创建配置文件：

**文件路径**：`resources/META-INF/rpc/com.rpc.serialize.Serializer`

**文件内容**：

```properties
kryo=com.rpc.serialize.impl.KryoSerializer
json=com.rpc.serialize.impl.JsonSerializer
hessian=com.rpc.serialize.impl.HessianSerializer
java=com.rpc.serialize.impl.JavaSerializer
```

### 4.4 使用 SPI 加载序列化器

```java
package com.rpc.serialize.factory;

import com.rpc.serialize.Serializer;
import com.rpc.spi.ExtensionFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 序列化器工厂
 */
@Slf4j
public class SerializerFactory {
    
    private static final Map<Byte, Serializer> SERIALIZERS = new ConcurrentHashMap<>();
    
    private static Serializer DEFAULT_SERIALIZER;
    
    static {
        // 使用 SPI 加载所有序列化器
        List<Serializer> serializerList = ExtensionFactory.getExtensions(Serializer.class);
        
        for (Serializer serializer : serializerList) {
            SERIALIZERS.put(serializer.getSerializerType(), serializer);
            log.info("加载序列化器: {} -> {}", 
                serializer.getSerializerType(), 
                serializer.getClass().getSimpleName());
        }
        
        // 获取默认序列化器
        DEFAULT_SERIALIZER = ExtensionFactory.getDefaultExtension(Serializer.class);
        log.info("默认序列化器: {}", DEFAULT_SERIALIZER.getClass().getSimpleName());
    }
    
    /**
     * 获取默认序列化器
     */
    public static Serializer getDefaultSerializer() {
        return DEFAULT_SERIALIZER;
    }
    
    /**
     * 根据类型获取序列化器
     */
    public static Serializer getSerializer(byte serializerType) {
        Serializer serializer = SERIALIZERS.get(serializerType);
        if (serializer == null) {
            log.warn("未找到序列化器类型: {}, 使用默认", serializerType);
            return DEFAULT_SERIALIZER;
        }
        return serializer;
    }
    
    /**
     * 根据名称获取序列化器
     */
    public static Serializer getSerializer(String name) {
        return ExtensionFactory.getExtension(Serializer.class, name);
    }
}
```

---

## 五、应用 SPI 扩展负载均衡策略

### 5.1 定义负载均衡接口

```java
package com.rpc.loadbalance;

import com.rpc.spi.SPI;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * 负载均衡接口
 */
@SPI("random")
public interface LoadBalancer {
    
    /**
     * 选择一个服务地址
     * 
     * @param serviceName 服务名称
     * @param addresses 可用地址列表
     * @return 选中的地址
     */
    InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses);
}
```

### 5.2 实现各种负载均衡策略

#### RandomLoadBalancer

```java
package com.rpc.loadbalance.impl;

import com.rpc.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Random;

/**
 * 随机负载均衡
 */
@Slf4j
public class RandomLoadBalancer implements LoadBalancer {
    
    private final Random random = new Random();
    
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        
        int index = random.nextInt(addresses.size());
        InetSocketAddress selected = addresses.get(index);
        
        log.debug("随机负载均衡选择: {} -> {}", serviceName, selected);
        return selected;
    }
}
```

#### RoundRobinLoadBalancer

```java
package com.rpc.loadbalance.impl;

import com.rpc.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡
 */
@Slf4j
public class RoundRobinLoadBalancer implements LoadBalancer {
    
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        
        AtomicInteger counter = counters.computeIfAbsent(serviceName, k -> new AtomicInteger(0));
        int index = Math.abs(counter.getAndIncrement()) % addresses.size();
        
        InetSocketAddress selected = addresses.get(index);
        log.debug("轮询负载均衡选择: {} -> {}", serviceName, selected);
        return selected;
    }
}
```

#### ConsistentHashLoadBalancer

```java
package com.rpc.loadbalance.impl;

import com.rpc.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 一致性哈希负载均衡
 */
@Slf4j
public class ConsistentHashLoadBalancer implements LoadBalancer {
    
    private final Map<String, ConsistentHash> consistentHashMap = new ConcurrentHashMap<>();
    
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        
        ConsistentHash consistentHash = consistentHashMap.computeIfAbsent(
            serviceName, k -> new ConsistentHash(addresses));
        
        // 使用服务名作为 key 进行哈希
        InetSocketAddress selected = consistentHash.get(serviceName);
        log.debug("一致性哈希负载均衡选择: {} -> {}", serviceName, selected);
        return selected;
    }
    
    /**
     * 一致性哈希算法实现
     */
    private static class ConsistentHash {
        private static final int VIRTUAL_NODES = 160;
        
        private final SortedMap<Integer, InetSocketAddress> ring = new TreeMap<>();
        
        public ConsistentHash(List<InetSocketAddress> addresses) {
            for (InetSocketAddress address : addresses) {
                for (int i = 0; i < VIRTUAL_NODES; i++) {
                    int hash = hash(address.toString() + "#" + i);
                    ring.put(hash, address);
                }
            }
        }
        
        public InetSocketAddress get(String key) {
            int hash = hash(key);
            
            SortedMap<Integer, InetSocketAddress> tailMap = ring.tailMap(hash);
            int nodeHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
            
            return ring.get(nodeHash);
        }
        
        private int hash(String key) {
            return key.hashCode() & Integer.MAX_VALUE;
        }
    }
}
```

#### LeastConnectionsLoadBalancer

```java
package com.rpc.loadbalance.impl;

import com.rpc.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最少连接数负载均衡
 */
@Slf4j
public class LeastConnectionsLoadBalancer implements LoadBalancer {
    
    private final Map<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();
    
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        
        InetSocketAddress selected = null;
        int minConnections = Integer.MAX_VALUE;
        
        for (InetSocketAddress address : addresses) {
            String key = address.toString();
            AtomicInteger counter = connectionCounts.computeIfAbsent(key, k -> new AtomicInteger(0));
            int connections = counter.get();
            
            if (connections < minConnections) {
                minConnections = connections;
                selected = address;
            }
        }
        
        if (selected != null) {
            connectionCounts.get(selected.toString()).incrementAndGet();
        }
        
        log.debug("最少连接数负载均衡选择: {} -> {} (连接数: {})", 
            serviceName, selected, minConnections);
        return selected;
    }
    
    /**
     * 释放连接（减少计数）
     */
    public void releaseConnection(InetSocketAddress address) {
        AtomicInteger counter = connectionCounts.get(address.toString());
        if (counter != null) {
            counter.decrementAndGet();
        }
    }
}
```

### 5.3 配置 SPI 扩展

**文件路径**：`resources/META-INF/rpc/com.rpc.loadbalance.LoadBalancer`

**文件内容**：

```properties
random=com.rpc.loadbalance.impl.RandomLoadBalancer
roundrobin=com.rpc.loadbalance.impl.RoundRobinLoadBalancer
consistenthash=com.rpc.loadbalance.impl.ConsistentHashLoadBalancer
leastconnections=com.rpc.loadbalance.impl.LeastConnectionsLoadBalancer
```

### 5.4 使用 SPI 加载负载均衡器

```java
package com.rpc.loadbalance.factory;

import com.rpc.loadbalance.LoadBalancer;
import com.rpc.spi.ExtensionFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * 负载均衡器工厂
 */
@Slf4j
public class LoadBalancerFactory {
    
    private static LoadBalancer DEFAULT_LOAD_BALANCER;
    
    static {
        DEFAULT_LOAD_BALANCER = ExtensionFactory.getDefaultExtension(LoadBalancer.class);
        log.info("默认负载均衡器: {}", DEFAULT_LOAD_BALANCER.getClass().getSimpleName());
    }
    
    /**
     * 获取默认负载均衡器
     */
    public static LoadBalancer getDefaultLoadBalancer() {
        return DEFAULT_LOAD_BALANCER;
    }
    
    /**
     * 根据名称获取负载均衡器
     */
    public static LoadBalancer getLoadBalancer(String name) {
        if (name == null || name.isEmpty()) {
            return DEFAULT_LOAD_BALANCER;
        }
        return ExtensionFactory.getExtension(LoadBalancer.class, name);
    }
}
```

---

## 六、测试验证

### 6.1 SPI 加载器测试

```java
package com.rpc.spi;

import com.rpc.serialize.Serializer;
import com.rpc.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * SPI 扩展加载器测试
 */
@Slf4j
public class ExtensionLoaderTest {
    
    @Before
    public void setUp() {
        log.info("========== SPI 测试开始 ==========");
    }
    
    /**
     * 测试 1：加载序列化器扩展
     */
    @Test
    public void testLoadSerializerExtensions() {
        log.info("\n========== 测试 1：加载序列化器扩展 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        
        // 获取所有支持的扩展名称
        Set<String> names = loader.getSupportedExtensions();
        log.info("支持的序列化器: {}", names);
        
        assertNotNull("扩展名称列表不应为空", names);
        assertTrue("应该至少有一个序列化器", names.size() > 0);
        assertTrue("应该包含 kryo", names.contains("kryo"));
        assertTrue("应该包含 json", names.contains("json"));
        assertTrue("应该包含 hessian", names.contains("hessian"));
        
        log.info("✓ 成功加载 {} 个序列化器扩展", names.size());
    }
    
    /**
     * 测试 2：根据名称获取扩展实例
     */
    @Test
    public void testGetExtensionByName() {
        log.info("\n========== 测试 2：根据名称获取扩展实例 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        
        // 获取 kryo 序列化器
        Serializer kryoSerializer = loader.getExtension("kryo");
        assertNotNull("kryo 序列化器不应为空", kryoSerializer);
        assertEquals("类型应该是 KRYO", Serializer.KRYO, kryoSerializer.getSerializerType());
        log.info("kryo 序列化器类型: {}", kryoSerializer.getClass().getSimpleName());
        
        // 获取 json 序列化器
        Serializer jsonSerializer = loader.getExtension("json");
        assertNotNull("json 序列化器不应为空", jsonSerializer);
        assertEquals("类型应该是 JSON", Serializer.JSON, jsonSerializer.getSerializerType());
        log.info("json 序列化器类型: {}", jsonSerializer.getClass().getSimpleName());
        
        log.info("✓ 根据名称获取扩展实例成功");
    }
    
    /**
     * 测试 3：获取默认扩展
     */
    @Test
    public void testGetDefaultExtension() {
        log.info("\n========== 测试 3：获取默认扩展 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        
        // 获取默认序列化器
        Serializer defaultSerializer = loader.getDefaultExtension();
        assertNotNull("默认序列化器不应为空", defaultSerializer);
        
        log.info("默认序列化器: {}", defaultSerializer.getClass().getSimpleName());
        log.info("默认序列化器类型: {}", defaultSerializer.getSerializerType());
        
        log.info("✓ 获取默认扩展成功");
    }
    
    /**
     * 测试 4：单例验证
     */
    @Test
    public void testSingletonInstance() {
        log.info("\n========== 测试 4：单例验证 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        
        // 多次获取同一个扩展
        Serializer s1 = loader.getExtension("kryo");
        Serializer s2 = loader.getExtension("kryo");
        
        assertSame("应该是同一个实例", s1, s2);
        log.info("实例 1: {}", s1.hashCode());
        log.info("实例 2: {}", s2.hashCode());
        
        log.info("✓ 单例验证通过");
    }
    
    /**
     * 测试 5：获取所有扩展实例
     */
    @Test
    public void testGetAllExtensions() {
        log.info("\n========== 测试 5：获取所有扩展实例 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        
        List<Serializer> serializers = loader.getExtensions();
        assertNotNull("序列化器列表不应为空", serializers);
        assertTrue("应该至少有一个序列化器", serializers.size() > 0);
        
        for (Serializer serializer : serializers) {
            log.info("序列化器: {}, 类型: {}", 
                serializer.getClass().getSimpleName(), 
                serializer.getSerializerType());
        }
        
        log.info("✓ 获取所有扩展实例成功");
    }
    
    /**
     * 测试 6：加载负载均衡器扩展
     */
    @Test
    public void testLoadLoadBalancerExtensions() {
        log.info("\n========== 测试 6：加载负载均衡器扩展 ==========");
        
        ExtensionLoader<LoadBalancer> loader = ExtensionLoader.getExtensionLoader(LoadBalancer.class);
        
        Set<String> names = loader.getSupportedExtensions();
        log.info("支持的负载均衡器: {}", names);
        
        assertNotNull("扩展名称列表不应为空", names);
        assertTrue("应该至少有一个负载均衡器", names.size() > 0);
        
        for (String name : names) {
            LoadBalancer loadBalancer = loader.getExtension(name);
            log.info("负载均衡器: {}", loadBalancer.getClass().getSimpleName());
        }
        
        log.info("✓ 成功加载 {} 个负载均衡器扩展", names.size());
    }
    
    /**
     * 测试 7：ExtensionFactory 测试
     */
    @Test
    public void testExtensionFactory() {
        log.info("\n========== 测试 7：ExtensionFactory 测试 ==========");
        
        // 使用工厂获取默认序列化器
        Serializer defaultSerializer = ExtensionFactory.getDefaultExtension(Serializer.class);
        assertNotNull("默认序列化器不应为空", defaultSerializer);
        log.info("默认序列化器: {}", defaultSerializer.getClass().getSimpleName());
        
        // 使用工厂根据名称获取序列化器
        Serializer jsonSerializer = ExtensionFactory.getExtension(Serializer.class, "json");
        assertNotNull("json 序列化器不应为空", jsonSerializer);
        log.info("json 序列化器: {}", jsonSerializer.getClass().getSimpleName());
        
        // 使用工厂获取所有序列化器
        List<Serializer> allSerializers = ExtensionFactory.getExtensions(Serializer.class);
        assertTrue("应该有多个序列化器", allSerializers.size() > 1);
        log.info("序列化器数量: {}", allSerializers.size());
        
        log.info("✓ ExtensionFactory 测试通过");
    }
    
    /**
     * 测试 8：不存在的扩展
     */
    @Test(expected = IllegalStateException.class)
    public void testNonExistentExtension() {
        log.info("\n========== 测试 8：不存在的扩展 ==========");
        
        ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
        
        // 尝试获取不存在的扩展，应该抛出异常
        loader.getExtension("nonexistent");
    }
}
```

### 6.2 序列化器 SPI 测试

```java
package com.rpc.serialize;

import com.rpc.serialize.factory.SerializerFactory;
import com.rpc.model.User;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * 序列化器 SPI 测试
 */
@Slf4j
public class SerializerSpiTest {
    
    @Before
    public void setUp() {
        log.info("========== 序列化器 SPI 测试开始 ==========");
    }
    
    /**
     * 测试 1：通过 SPI 加载默认序列化器
     */
    @Test
    public void testGetDefaultSerializer() {
        log.info("\n========== 测试 1：获取默认序列化器 ==========");
        
        Serializer serializer = SerializerFactory.getDefaultSerializer();
        assertNotNull("默认序列化器不应为空", serializer);
        
        log.info("默认序列化器: {}", serializer.getClass().getSimpleName());
        log.info("✓ 获取默认序列化器成功");
    }
    
    /**
     * 测试 2：测试各种序列化器的序列化和反序列化
     */
    @Test
    public void testSerializeAndDeserialize() {
        log.info("\n========== 测试 2：序列化和反序列化测试 ==========");
        
        User user = new User();
        user.setId(1L);
        user.setName("张三");
        
        String[] serializerNames = {"kryo", "json", "hessian", "java"};
        
        for (String name : serializerNames) {
            log.info("--- 测试 {} 序列化器 ---", name);
            
            Serializer serializer = SerializerFactory.getSerializer(name);
            assertNotNull(name + " 序列化器不应为空", serializer);
            
            // 序列化
            byte[] bytes = serializer.serialize(user);
            assertNotNull("序列化结果不应为空", bytes);
            log.info("{} 序列化后大小: {} bytes", name, bytes.length);
            
            // 反序列化
            User deserializedUser = serializer.deserialize(bytes, User.class);
            assertNotNull("反序列化结果不应为空", deserializedUser);
            assertEquals("ID 应该相等", user.getId(), deserializedUser.getId());
            assertEquals("名称应该相等", user.getName(), deserializedUser.getName());
            
            log.info("反序列化结果: {}", deserializedUser);
            log.info("✓ {} 序列化器测试通过\n", name);
        }
    }
    
    /**
     * 测试 3：根据类型获取序列化器
     */
    @Test
    public void testGetSerializerByType() {
        log.info("\n========== 测试 3：根据类型获取序列化器 ==========");
        
        Serializer kryoSerializer = SerializerFactory.getSerializer(Serializer.KRYO);
        assertNotNull("kryo 序列化器不应为空", kryoSerializer);
        assertEquals("类型应该是 KRYO", Serializer.KRYO, kryoSerializer.getSerializerType());
        
        Serializer jsonSerializer = SerializerFactory.getSerializer(Serializer.JSON);
        assertNotNull("json 序列化器不应为空", jsonSerializer);
        assertEquals("类型应该是 JSON", Serializer.JSON, jsonSerializer.getSerializerType());
        
        log.info("✓ 根据类型获取序列化器成功");
    }
    
    /**
     * 测试 4：序列化性能对比
     */
    @Test
    public void testSerializerPerformance() {
        log.info("\n========== 测试 4：序列化性能对比 ==========");
        
        User user = new User();
        user.setId(1L);
        user.setName("张三");
        
        String[] serializerNames = {"kryo", "hessian", "java", "json"};
        int iterations = 1000;
        
        for (String name : serializerNames) {
            Serializer serializer = SerializerFactory.getSerializer(name);
            
            long startTime = System.nanoTime();
            
            for (int i = 0; i < iterations; i++) {
                byte[] bytes = serializer.serialize(user);
                serializer.deserialize(bytes, User.class);
            }
            
            long endTime = System.nanoTime();
            long duration = (endTime - startTime) / 1_000_000;
            
            log.info("{}: {} 次序列化+反序列化耗时 {} ms", name, iterations, duration);
        }
        
        log.info("✓ 性能对比测试完成");
    }
}
```

### 6.3 负载均衡器 SPI 测试

```java
package com.rpc.loadbalance;

import com.rpc.loadbalance.factory.LoadBalancerFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 负载均衡器 SPI 测试
 */
@Slf4j
public class LoadBalancerSpiTest {
    
    private List<InetSocketAddress> addresses;
    
    @Before
    public void setUp() {
        log.info("========== 负载均衡器 SPI 测试开始 ==========");
        
        addresses = new ArrayList<>();
        addresses.add(new InetSocketAddress("192.168.1.1", 8080));
        addresses.add(new InetSocketAddress("192.168.1.2", 8080));
        addresses.add(new InetSocketAddress("192.168.1.3", 8080));
    }
    
    /**
     * 测试 1：获取默认负载均衡器
     */
    @Test
    public void testGetDefaultLoadBalancer() {
        log.info("\n========== 测试 1：获取默认负载均衡器 ==========");
        
        LoadBalancer loadBalancer = LoadBalancerFactory.getDefaultLoadBalancer();
        assertNotNull("默认负载均衡器不应为空", loadBalancer);
        
        log.info("默认负载均衡器: {}", loadBalancer.getClass().getSimpleName());
        log.info("✓ 获取默认负载均衡器成功");
    }
    
    /**
     * 测试 2：根据名称获取负载均衡器
     */
    @Test
    public void testGetLoadBalancerByName() {
        log.info("\n========== 测试 2：根据名称获取负载均衡器 ==========");
        
        String[] names = {"random", "roundrobin", "consistenthash", "leastconnections"};
        
        for (String name : names) {
            LoadBalancer loadBalancer = LoadBalancerFactory.getLoadBalancer(name);
            assertNotNull(name + " 负载均衡器不应为空", loadBalancer);
            log.info("{}: {}", name, loadBalancer.getClass().getSimpleName());
        }
        
        log.info("✓ 根据名称获取负载均衡器成功");
    }
    
    /**
     * 测试 3：随机负载均衡
     */
    @Test
    public void testRandomLoadBalancer() {
        log.info("\n========== 测试 3：随机负载均衡测试 ==========");
        
        LoadBalancer loadBalancer = LoadBalancerFactory.getLoadBalancer("random");
        Map<String, Integer> distribution = new HashMap<>();
        
        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            InetSocketAddress selected = loadBalancer.select("test-service", addresses);
            String key = selected.toString();
            distribution.put(key, distribution.getOrDefault(key, 0) + 1);
        }
        
        log.info("随机负载均衡分布:");
        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            double percentage = (entry.getValue() * 100.0) / iterations;
            log.info("  {}: {} 次 ({:.2f}%)", entry.getKey(), entry.getValue(), percentage);
        }
        
        log.info("✓ 随机负载均衡测试通过");
    }
    
    /**
     * 测试 4：轮询负载均衡
     */
    @Test
    public void testRoundRobinLoadBalancer() {
        log.info("\n========== 测试 4：轮询负载均衡测试 ==========");
        
        LoadBalancer loadBalancer = LoadBalancerFactory.getLoadBalancer("roundrobin");
        
        log.info("轮询顺序:");
        for (int i = 0; i < 10; i++) {
            InetSocketAddress selected = loadBalancer.select("test-service", addresses);
            log.info("  第 {} 次选择: {}", i + 1, selected);
        }
        
        log.info("✓ 轮询负载均衡测试通过");
    }
    
    /**
     * 测试 5：一致性哈希负载均衡
     */
    @Test
    public void testConsistentHashLoadBalancer() {
        log.info("\n========== 测试 5：一致性哈希负载均衡测试 ==========");
        
        LoadBalancer loadBalancer = LoadBalancerFactory.getLoadBalancer("consistenthash");
        
        String[] services = {"user-service", "order-service", "payment-service"};
        
        for (String service : services) {
            // 一致性哈希对同一服务名应该返回相同地址
            InetSocketAddress selected1 = loadBalancer.select(service, addresses);
            InetSocketAddress selected2 = loadBalancer.select(service, addresses);
            
            log.info("服务 {} -> {}", service, selected1);
            assertEquals("同一服务应该路由到同一地址", selected1, selected2);
        }
        
        log.info("✓ 一致性哈希负载均衡测试通过");
    }
    
    /**
     * 测试 6：负载均衡器切换
     */
    @Test
    public void testSwitchLoadBalancer() {
        log.info("\n========== 测试 6：负载均衡器切换测试 ==========");
        
        String serviceName = "test-service";
        
        // 使用随机负载均衡
        LoadBalancer randomLb = LoadBalancerFactory.getLoadBalancer("random");
        InetSocketAddress selected1 = randomLb.select(serviceName, addresses);
        log.info("随机负载均衡选择: {}", selected1);
        
        // 切换到轮询负载均衡
        LoadBalancer roundRobinLb = LoadBalancerFactory.getLoadBalancer("roundrobin");
        InetSocketAddress selected2 = roundRobinLb.select(serviceName, addresses);
        log.info("轮询负载均衡选择: {}", selected2);
        
        // 切换到一致性哈希
        LoadBalancer hashLb = LoadBalancerFactory.getLoadBalancer("consistenthash");
        InetSocketAddress selected3 = hashLb.select(serviceName, addresses);
        log.info("一致性哈希负载均衡选择: {}", selected3);
        
        log.info("✓ 负载均衡器切换测试通过");
    }
}
```

---

## 七、SPI 高级特性

### 7.1 自动注入支持

SPI 支持通过 `@Inject` 注解实现扩展实例的自动注入，让扩展点可以方便地依赖其他扩展。

#### @Inject 注解定义

```java
package com.rpc.spi;

import java.lang.annotation.*;

/**
 * 依赖注入注解
 * 
 * 用于标注需要注入的扩展实例字段
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Inject {
    
    /**
     * 注入的扩展名称
     * 为空则使用默认扩展
     */
    String value() default "";
    
    /**
     * 是否必须注入
     * 如果为 true，注入失败会抛出异常
     * 如果为 false，注入失败只记录警告日志
     */
    boolean required() default true;
}
```

#### @Initialize 注解定义

```java
package com.rpc.spi;

import java.lang.annotation.*;

/**
 * 初始化方法注解
 * 
 * 用于标注扩展实例创建后需要执行的初始化方法
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Initialize {
}
```

### 7.2 使用示例

#### 定义扩展点接口

```java
package com.rpc.spi.example;

import com.rpc.spi.SPI;

/**
 * 数据处理器接口
 */
@SPI("default")
public interface DataProcessor {
    
    /**
     * 处理数据
     */
    String process(String data);
    
    /**
     * 获取处理器名称
     */
    String getName();
}
```

#### 实现带依赖注入的扩展

```java
package com.rpc.spi.example;

import com.rpc.serialize.Serializer;
import com.rpc.spi.Initialize;
import com.rpc.spi.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认数据处理器
 */
@Slf4j
@Getter
public class DefaultDataProcessor implements DataProcessor {
    
    /**
     * 注入默认序列化器
     */
    @Inject
    private Serializer serializer;
    
    /**
     * 注入指定名称的序列化器
     */
    @Inject("json")
    private Serializer jsonSerializer;
    
    /**
     * 可选注入，失败不报错
     */
    @Inject(value = "nonexistent", required = false)
    private Serializer optionalSerializer;
    
    private boolean initialized = false;
    
    @Override
    public String process(String data) {
        byte[] bytes = serializer.serialize(data);
        String result = serializer.deserialize(bytes, String.class);
        return "Processed by " + getName() + ": " + result;
    }
    
    @Override
    public String getName() {
        return "DefaultDataProcessor";
    }
    
    /**
     * 初始化方法
     */
    @Initialize
    public void init() {
        this.initialized = true;
        log.info("DefaultDataProcessor 初始化完成");
    }
}
```

#### 多依赖注入示例

```java
package com.rpc.spi.example;

import com.rpc.loadbalance.LoadBalancer;
import com.rpc.serialize.Serializer;
import com.rpc.spi.Initialize;
import com.rpc.spi.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 高级数据处理器 - 多依赖注入
 */
@Slf4j
@Getter
public class AdvancedDataProcessor implements DataProcessor {
    
    /**
     * 注入 Kryo 序列化器
     */
    @Inject("kryo")
    private Serializer serializer;
    
    /**
     * 注入轮询负载均衡器
     */
    @Inject("roundrobin")
    private LoadBalancer loadBalancer;
    
    private boolean initialized = false;
    
    @Override
    public String process(String data) {
        byte[] bytes = serializer.serialize(data);
        return serializer.deserialize(bytes, String.class);
    }
    
    @Override
    public String getName() {
        return "AdvancedDataProcessor";
    }
    
    @Initialize
    public void init() {
        this.initialized = true;
        log.info("AdvancedDataProcessor 初始化完成");
    }
}
```

### 7.3 扩展增强的 ExtensionLoader

```java
/**
 * 创建扩展实例（支持依赖注入和初始化）
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
                // 关键：先检查扩展是否存在，避免 getExtension 抛出异常
                if (loader.hasExtension(injectName)) {
                    dependency = loader.getExtension(injectName);
                } else {
                    // 扩展不存在
                    if (required) {
                        throw new IllegalStateException("找不到扩展: " + injectName);
                    } else {
                        log.debug("可选扩展不存在，跳过注入: {}", injectName);
                        return;
                    }
                }
            } else {
                dependency = loader.getDefaultExtension();
            }
        } else {
            dependency = getBeanFromContainer(fieldType, injectName);
        }
        
        if (dependency == null) {
            if (required) {
                throw new IllegalStateException("无法注入依赖: " + field.getName());
            } else {
                log.debug("可选依赖未找到，跳过注入: {}", field.getName());
                return;
            }
        }
        
        field.setAccessible(true);
        field.set(instance, dependency);
        
    } catch (IllegalAccessException e) {
        if (required) {
            throw new RuntimeException("注入依赖失败: " + field.getName(), e);
        }
    } catch (IllegalStateException e) {
        if (required) {
            throw e;
        } else {
            log.debug("可选依赖加载失败，跳过注入: {}", e.getMessage());
        }
    }
}

/**
 * 调用初始化方法
 */
private void invokeInitializeMethod(Object instance) {
    for (Method method : instance.getClass().getMethods()) {
        if (method.isAnnotationPresent(Initialize.class)) {
            try {
                method.setAccessible(true);
                method.invoke(instance);
            } catch (Exception e) {
                log.warn("调用初始化方法失败: {}", method.getName(), e);
            }
        }
    }
}
```

### 7.4 依赖注入特性

| 特性 | 说明 |
|------|------|
| **自动注入** | 扩展实例创建时自动注入依赖 |
| **指定名称** | 通过 `@Inject("name")` 指定注入哪个扩展 |
| **默认注入** | `@Inject` 不指定名称时注入默认扩展 |
| **可选注入** | `required=false` 时注入失败不报错 |
| **循环依赖检测** | 自动检测并阻止循环依赖 |
| **初始化回调** | 通过 `@Initialize` 注解标记初始化方法 |
| **单例保证** | 注入的是同一个扩展实例 |

### 7.5 依赖注入测试

```java
package com.rpc.spi;

import com.rpc.spi.example.DataProcessor;
import com.rpc.spi.example.DefaultDataProcessor;
import com.rpc.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * SPI 依赖注入功能测试
 */
@Slf4j
public class SpiInjectTest {
    
    /**
     * 测试基本依赖注入
     */
    @Test
    public void testBasicInject() {
        DataProcessor processor = ExtensionFactory.getExtension(
            DataProcessor.class, "default");
        
        assertNotNull("处理器不应为空", processor);
        assertTrue("应该是 DefaultDataProcessor", 
            processor instanceof DefaultDataProcessor);
        
        DefaultDataProcessor defaultProcessor = (DefaultDataProcessor) processor;
        
        // 验证注入的序列化器
        Serializer serializer = defaultProcessor.getSerializer();
        assertNotNull("序列化器应该被注入", serializer);
        
        // 验证注入的 JSON 序列化器
        Serializer jsonSerializer = defaultProcessor.getJsonSerializer();
        assertNotNull("JSON 序列化器应该被注入", jsonSerializer);
        assertEquals("应该是 JSON 序列化器", 2, jsonSerializer.getSerializerType());
        
        // 关键测试：验证可选注入不存在的扩展应该为 null（不会抛出异常）
        Serializer optionalSerializer = defaultProcessor.getOptionalSerializer();
        assertNull("可选注入不存在的扩展应该为 null", optionalSerializer);
        
        // 验证初始化方法被调用
        assertTrue("初始化方法应该被调用", defaultProcessor.isInitialized());
    }
    
    /**
     * 测试 required=false 且扩展不存在（关键测试）
     */
    @Test
    public void testOptionalInjectWithNonexistentExtension() {
        // OptionalInjectProcessor 包含一个 required=false 且不存在的扩展
        OptionalInjectProcessor processor = (OptionalInjectProcessor) 
            ExtensionFactory.getExtension(DataProcessor.class, "optional");
        
        assertNotNull("处理器不应为空", processor);
        
        // 必须注入的扩展应该正常注入
        assertNotNull("必须注入的序列化器不应为空", 
            processor.getRequiredSerializer());
        
        // 关键：可选注入不存在的扩展应该为 null（不会抛出异常）
        assertNull("可选注入不存在的扩展应该为 null", 
            processor.getOptionalNonexistentSerializer());
        
        // 可选注入存在的扩展应该正常注入
        assertNotNull("可选注入存在的扩展不应为空", 
            processor.getOptionalExistingSerializer());
    }
    
    /**
     * 测试注入实例是单例
     */
    @Test
    public void testInjectSingleton() {
        Serializer defaultSerializer = ExtensionFactory.getDefaultExtension(
            Serializer.class);
        
        DefaultDataProcessor processor = (DefaultDataProcessor) 
            ExtensionFactory.getExtension(DataProcessor.class, "default");
        
        // 验证注入的是同一个实例
        assertSame("注入的应该是同一个序列化器实例", 
            defaultSerializer, processor.getSerializer());
    }
}
```

---

## 八、本课总结

### 核心知识点

1. **SPI 机制原理**
   - 接口与实现解耦
   - 运行时动态加载
   - 配置文件驱动

2. **Java 原生 SPI**
   - ServiceLoader 类
   - META-INF/services 配置
   - 延迟加载机制

3. **自定义 SPI 实现**
   - ExtensionLoader 核心类
   - 单例缓存优化
   - 默认扩展支持
   - 依赖注入支持

4. **SPI 应用场景**
   - 序列化器扩展
   - 负载均衡策略扩展
   - 注册中心扩展

5. **依赖注入特性**
   - @Inject 注解自动注入
   - 指定名称注入
   - 可选注入支持
   - 初始化方法回调
   - 循环依赖检测

### 高内聚低耦合设计

```
┌─────────────────────────────────────────────────────────┐
│                    SPI 模块职责划分                      │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  @SPI 注解                                              │
│    职责：标记扩展点接口，指定默认实现                   │
│    依赖：无                                             │
│                                                         │
│  @Inject 注解                                           │
│    职责：标记需要注入的字段                             │
│    依赖：无                                             │
│                                                         │
│  @Initialize 注解                                       │
│    职责：标记初始化方法                                 │
│    依赖：无                                             │
│                                                         │
│  ExtensionLoader<T>                                     │
│    职责：加载和管理扩展实现，支持依赖注入               │
│    依赖：@SPI, @Inject, @Initialize                    │
│                                                         │
│  ExtensionFactory                                       │
│    职责：提供统一的扩展获取入口                         │
│    依赖：ExtensionLoader                                │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**设计优势：**

1. ✅ **高内聚**：每个类职责单一明确
2. ✅ **低耦合**：通过接口解耦，实现可插拔
3. ✅ **易扩展**：新增实现只需添加配置
4. ✅ **易测试**：每个组件可独立测试
5. ✅ **自动注入**：扩展依赖自动注入，无需手动管理

---

## 九、课后思考

1. **SPI 与工厂模式的区别？**
   - 提示：关注配置化和动态性

2. **如何实现扩展的热加载？**
   - 提示：监听配置文件变化

3. **如何实现扩展的优先级排序？**
   - 提示：使用 @Priority 注解

4. **Dubbo 的 SPI 与 Java SPI 有什么区别？**
   - 提示：性能优化、AOP 支持、依赖注入

---

## 十、动手练习

### 练习 1：实现注册中心扩展

使用 SPI 机制支持多种注册中心（ZooKeeper、Nacos、Consul）。

提示：
```java
@SPI("zookeeper")
public interface ServiceRegistry {
    void register(String serviceName, String address);
    void unregister(String serviceName);
    List<String> lookup(String serviceName);
}
```

### 练习 2：实现配置中心扩展

使用 SPI 机制支持多种配置中心。

提示：
```java
@SPI("properties")
public interface ConfigSource {
    String getString(String key);
    Integer getInteger(String key);
    void setString(String key, String value);
}
```

### 练习 3：实现扩展的优先级

为扩展添加优先级支持，自动选择优先级最高的实现。

提示：
```java
@SPI(value = "kryo", priority = 100)
public interface Serializer {
    // ...
}
```

---

## 十一、下一步

下一节课我们将实现**容错与重试机制**，让 RPC 框架更加健壮可靠。

**[跳转到第 12 课：容错与重试机制](./lesson-12-fault-tolerance.md)**

**[返回课程目录](./README.md)**