# 第 3 课：序列化协议

## 学习目标

- 理解序列化的作用及其在 RPC 中的重要性
- 掌握常见序列化协议的优缺点
- 学会使用 Kryo 高性能序列化框架
- 实现 RPC 框架的序列化工具类

---

## 一、什么是序列化？为什么需要序列化？

### 1.1 序列化的定义

**序列化（Serialization）** 是指将数据结构或对象转换成二进制字节流的过程。  
**反序列化（Deserialization）** 是指将二进制字节流还原成数据结构或对象的过程。

```
Java 对象 ──────→ 字节数组  （序列化）
字节数组 ──────→ Java 对象  （反序列化）
```

### 1.2 为什么 RPC 需要序列化？

RPC 调用过程中，客户端和服务端通过网络通信，而网络传输的是**字节流**，不是 Java 对象。

```
客户端                                    服务端
┌──────────────┐                         ┌──────────────┐
│  Java 对象    │   ①序列化               │  字节流      │
│  User 对象     │ ─────────→ [字节流] ───→ │              │
└──────────────┘                         └──────────────┘
                                              ↓
                                         ②反序列化
                                              ↓
                                        ┌──────────────┐
                                        │  Java 对象    │
                                        │  User 对象     │
                                        └──────────────┘
```

**核心原因**：
1. **网络传输需求**：网络只能传输字节数据，不能直接传输对象
2. **跨平台需求**：不同语言、不同系统之间需要统一的数据格式
3. **持久化需求**：对象可以序列化后存储到文件或数据库

### 1.3 序列化的场景

- ✅ **RPC 远程调用**：方法参数和返回值的传递
- ✅ **分布式缓存**：Redis 等缓存中存储对象
- ✅ **消息队列**：Kafka、RabbitMQ 中传递消息
- ✅ **会话复制**：Tomcat 集群间共享 Session
- ✅ **对象持久化**：将对象保存到磁盘

---

## 二、常见序列化方案对比

### 2.1 Java 原生序列化

Java 自带的序列化方式，实现 `Serializable` 接口即可。

#### 示例代码

```java
import java.io.*;

// 1. 实现 Serializable 接口
class User implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String name;
    private Integer age;
    
    // 构造方法、getter、setter 省略
}

// 2. 序列化
public static byte[] serialize(Object obj) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ObjectOutputStream oos = new ObjectOutputStream(baos);
    oos.writeObject(obj);
    return baos.toByteArray();
}

// 3. 反序列化
public static Object deserialize(byte[] bytes, Class<?> clazz) 
        throws IOException, ClassNotFoundException {
    ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
    ObjectInputStream ois = new ObjectInputStream(bais);
    return ois.readObject();
}
```

#### 优缺点分析

| 优点 | 缺点 |
|------|------|
| ✅ JDK 自带，无需额外依赖 | ❌ **性能差**：序列化后体积大 |
| ✅ 使用简单，实现容易 | ❌ **速度慢**：序列化/反序列化效率低 |
| ✅ 支持复杂对象图 | ❌ **安全性问题**：反序列化漏洞 |
| ✅ 自动处理循环引用 | ❌ **仅支持 Java 语言** |

#### 性能测试

```java
User user = new User("张三", 25);

// Java 原生序列化
byte[] javaBytes = serialize(user);  // 约 80 字节
long startTime = System.currentTimeMillis();
for (int i = 0; i < 10000; i++) {
    deserialize(javaBytes, User.class);
}
long endTime = System.currentTimeMillis();
System.out.println("Java 原生耗时：" + (endTime - startTime) + "ms");
```

**结论**：Java 原生序列化性能最差，**不推荐用于 RPC 框架**。

---

### 2.2 JSON 序列化

通过 JSON 格式进行序列化，常见的库有 Jackson、Gson、Fastjson。

#### 示例代码（Jackson）

```xml
<!-- Maven 依赖 -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.15.2</version>
</dependency>
```

```java
import com.fasterxml.jackson.databind.ObjectMapper;

class User {
    private String name;
    private Integer age;
    // getter、setter 省略
}

public class JsonTest {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        
        User user = new User("张三", 25);
        
        // 序列化
        String json = mapper.writeValueAsString(user);
        // {"name":"张三","age":25}
        
        // 反序列化
        User deserialized = mapper.readValue(json, User.class);
    }
}
```

#### 优缺点分析

| 优点 | 缺点 |
|------|------|
| ✅ **可读性好**：文本格式，易于调试 | ❌ **体积较大**：包含字段名 |
| ✅ **跨语言**：所有语言都支持 JSON | ❌ **性能一般**：不如二进制序列化 |
| ✅ **生态完善**：库多，文档丰富 | ❌ **不支持泛型**：需要特殊处理 |
| ✅ **人类可读**：便于日志记录和问题排查 | ❌ **类型信息丢失**：需要指定类型 |

#### 适用场景

- ✅ RESTful API
- ✅ 配置文件
- ✅ 日志记录
- ⚠️ **不推荐用于高性能 RPC 场景**

---

### 2.3 Protobuf（Protocol Buffers）

Google 开发的轻量级、高效的二进制序列化协议。

#### 特点

- ✅ **性能优秀**：序列化后体积小，速度快
- ✅ **跨语言**：支持 Java、C++、Python、Go 等
- ✅ **强类型**：基于 proto 文件定义数据结构
- ✅ **向后兼容**：支持字段增删

#### 使用步骤

**步骤 1：定义 proto 文件**

```protobuf
syntax = "proto3";

message User {
    string name = 1;
    int32 age = 2;
    string email = 3;
}
```

**步骤 2：编译生成 Java 类**

```bash
protoc --java_out=. user.proto
```

**步骤 3：使用生成的类**

```java
User user = User.newBuilder()
    .setName("张三")
    .setAge(25)
    .setEmail("zhangsan@example.com")
    .build();

byte[] bytes = user.toByteArray();

User deserialized = User.parseFrom(bytes);
```

#### 优缺点分析

| 优点 | 缺点 |
|------|------|
| ✅ **性能极佳**：比 JSON 小 3-10 倍，快 5-20 倍 | ❌ **需要编译**：必须先编译 proto 文件 |
| ✅ **跨语言支持好** | ❌ **可读性差**：二进制格式 |
| ✅ **成熟稳定**：Google 背书 | ❌ **学习成本**：需要学习 proto 语法 |
| ✅ **gRPC 默认序列化方式** | ❌ **灵活性差**：修改结构需要重新编译 |

#### 适用场景

- ✅ **高性能 RPC 调用**（如 gRPC）
- ✅ **微服务间通信**
- ✅ **对性能要求高的场景**

---

### 2.4 Kryo（本课程选用）

Twitter 开发的高性能 Java 序列化框架。

#### 特点

- ✅ **性能卓越**：比 Java 原生快 10 倍以上
- ✅ **使用简单**：无需预先定义结构
- ✅ **自动处理对象图**：支持复杂对象关系
- ✅ **广泛使用**：Storm、Spark 等都在用

#### Maven 依赖

```xml
<dependency>
    <groupId>com.esotericsoftware</groupId>
    <artifactId>kryo</artifactId>
    <version>5.5.0</version>
</dependency>
```

#### 基本使用

```java
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

class User {
    private String name;
    private Integer age;
    // 需要提供无参构造方法和 getter/setter
}

public class KryoTest {
    public static void main(String[] args) {
        Kryo kryo = new Kryo();
        
        User user = new User("张三", 25);
        
        // 序列化
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Output output = new Output(baos);
        kryo.writeObject(output, user);
        byte[] bytes = output.toBytes();
        output.close();
        
        // 反序列化
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        Input input = new Input(bais);
        User deserialized = kryo.readObject(input, User.class);
        input.close();
    }
}
```

#### 优缺点分析

| 优点 | 缺点 |
|------|------|
| ✅ **性能优秀**：仅次于 Protobuf | ❌ **仅支持 Java**：不适合跨语言 |
| ✅ **使用简单**：无需预定义 schema | ❌ **版本兼容性差**：类结构变化可能失败 |
| ✅ **无需修改代码**：普通 Java 类即可 | ❌ **线程不安全**：需要 ThreadLocal 封装 |
| ✅ **支持泛型** | ❌ **不支持 null 值序列化**（默认配置下） |

#### 适用场景

- ✅ **纯 Java 应用的 RPC 框架** ⭐
- ✅ **对性能有要求的场景**
- ✅ **快速原型开发**

---

### 2.5 Hessian

Caucho 公司开发的轻量级 RPC 协议，支持跨语言。

#### 特点

- ✅ **跨语言**：Java、PHP、Python、.NET 等
- ✅ **自描述**：不需要预先定义数据结构
- ✅ **紧凑的二进制格式**

#### 优缺点分析

| 优点 | 缺点 |
|------|------|
| ✅ 跨语言支持好 | ❌ 性能一般：不如 Kryo 和 Protobuf |
| ✅ 使用简单 | ❌ 国内使用较少：资料相对少 |
| ✅ 支持复杂类型 | ❌ 安全性问题：历史上出现过漏洞 |

---

### 2.6 各序列化方案性能对比

根据业界测试数据（序列化 10000 次相同对象）：

| 序列化方式 | 序列化后大小 | 序列化耗时 | 反序列化耗时 | 综合评价 |
|-----------|------------|----------|------------|---------|
| Java 原生 | 80 字节 | 100ms | 150ms | ⭐⭐ |
| JSON | 45 字节 | 50ms | 60ms | ⭐⭐⭐ |
| **Kryo** | **25 字节** | **10ms** | **15ms** | ⭐⭐⭐⭐⭐ |
| Protobuf | 20 字节 | 8ms | 10ms | ⭐⭐⭐⭐⭐ |
| Hessian | 35 字节 | 25ms | 30ms | ⭐⭐⭐⭐ |

**结论**：
- **纯 Java 应用**：优先选择 Kryo（性能好、使用简单）
- **跨语言场景**：选择 Protobuf 或 Hessian
- **RESTful API**：选择 JSON
- **避免使用**：Java 原生序列化

---

## 三、为什么 RPC 框架选择 Kryo？

### 3.1 我们的技术选型理由

对于教学性质的 RPC 框架，我们选择 Kryo 作为默认序列化方案：

1. ✅ **学习成本低**：无需像 Protobuf 那样学习新的语法
2. ✅ **性能好**：满足高性能 RPC 的需求
3. ✅ **使用简单**：几行代码即可完成序列化
4. ✅ **专注于 RPC 核心**：不在序列化上花费太多精力
5. ✅ **可扩展**：后续可以轻松替换为其他序列化方式

### 3.2 设计可扩展的序列化接口

虽然默认使用 Kryo，但我们要设计良好的接口，方便后续扩展。

```java
package com.rpc.core.serialize;

/**
 * 序列化接口
 */
public interface Serializer {
    
    /**
     * 序列化
     * @param obj 要序列化的对象
     * @return 字节数组
     */
    byte[] serialize(Object obj);
    
    /**
     * 反序列化
     * @param bytes 字节数组
     * @param clazz 目标类型
     * @return 反序列化后的对象
     */
    <T> T deserialize(byte[] bytes, Class<T> clazz);
    
    /**
     * 获取序列化器类型标识
     * @return 类型标识（用于协议中标识使用哪种序列化方式）
     */
    int getSerializerType();
}
```

---

## 四、实现多种序列化器

### 4.1 Kryo 序列化器实现

```java
package com.rpc.core.serialize.impl;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.rpc.core.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Kryo 序列化器
 */
@Slf4j
public class KryoSerializer implements Serializer {
    
    public static final int TYPE_KRYO = 1;
    
    /**
     * 使用 ThreadLocal 保证线程安全
     * Kryo 不是线程安全的，需要每个线程独享一个实例
     */
    private static final ThreadLocal<Kryo> kryoLocal = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        // 配置 Kryo
        kryo.setReferences(true);      // 支持对象循环引用
        kryo.setRegistrationRequired(false);  // 不需要注册类（方便但性能略低）
        return kryo;
    });
    
    @Override
    public byte[] serialize(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             Output output = new Output(baos)) {
            
            Kryo kryo = kryoLocal.get();
            kryo.writeObject(output, obj);
            
            return output.toBytes();
        } catch (Exception e) {
            log.error("Kryo 序列化失败", e);
            throw new RuntimeException("Kryo 序列化失败", e);
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             Input input = new Input(bais)) {
            
            Kryo kryo = kryoLocal.get();
            return (T) kryo.readObject(input, clazz);
        } catch (Exception e) {
            log.error("Kryo 反序列化失败", e);
            throw new RuntimeException("Kryo 反序列化失败", e);
        }
    }
    
    @Override
    public int getSerializerType() {
        return TYPE_KRYO;
    }
}
```

### 4.2 JSON 序列化器实现（Jackson）

```java
package com.rpc.core.serialize.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpc.core.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON 序列化器（Jackson 实现）
 */
@Slf4j
public class JsonSerializer implements Serializer {
    
    public static final int TYPE_JSON = 2;
    
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    @Override
    public byte[] serialize(Object obj) {
        try {
            return MAPPER.writeValueAsBytes(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }
    
    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try {
            return MAPPER.readValue(bytes, clazz);
        } catch (Exception e) {
            log.error("JSON 反序列化失败", e);
            throw new RuntimeException("JSON 反序列化失败", e);
        }
    }
    
    @Override
    public int getSerializerType() {
        return TYPE_JSON;
    }
}
```

### 4.3 Java 原生序列化器实现

```java
package com.rpc.core.serialize.impl;

import com.rpc.core.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Java 原生序列化器（仅供对比测试使用）
 */
@Slf4j
public class JavaSerializer implements Serializer {
    
    public static final int TYPE_JAVA = 3;
    
    @Override
    public byte[] serialize(Object obj) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            
            oos.writeObject(obj);
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Java 原生序列化失败", e);
            throw new RuntimeException("Java 原生序列化失败", e);
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            
            return (T) ois.readObject();
        } catch (Exception e) {
            log.error("Java 原生反序列化失败", e);
            throw new RuntimeException("Java 原生反序列化失败", e);
        }
    }
    
    @Override
    public int getSerializerType() {
        return TYPE_JAVA;
    }
}
```

---

## 五、序列化器工厂

为了方便管理多种序列化器，我们创建一个工厂类。

```java
package com.rpc.core.serialize;

import com.rpc.core.serialize.impl.JavaSerializer;
import com.rpc.core.serialize.impl.JsonSerializer;
import com.rpc.core.serialize.impl.KryoSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * 序列化器工厂
 */
public class SerializerFactory {
    
    /** 存储所有可用的序列化器 */
    private static final Map<Integer, Serializer> SERIALIZER_MAP = new HashMap<>();
    
    /** 默认序列化器 */
    public static final Serializer DEFAULT_SERIALIZER = new KryoSerializer();
    
    static {
        // 注册各种序列化器
        SERIALIZER_MAP.put(KryoSerializer.TYPE_KRYO, new KryoSerializer());
        SERIALIZER_MAP.put(JsonSerializer.TYPE_JSON, new JsonSerializer());
        SERIALIZER_MAP.put(JavaSerializer.TYPE_JAVA, new JavaSerializer());
    }
    
    /**
     * 根据类型获取序列化器
     * @param type 序列化器类型标识
     * @return 序列化器实例
     */
    public static Serializer getSerializer(int type) {
        return SERIALIZER_MAP.getOrDefault(type, DEFAULT_SERIALIZER);
    }
    
    /**
     * 获取默认序列化器
     */
    public static Serializer getDefaultSerializer() {
        return DEFAULT_SERIALIZER;
    }
}
```

---

## 六、序列化器测试

### 6.1 创建测试对象

```java
package com.rpc.example.api.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class User implements Serializable {
    private String name;
    private Integer age;
    private String email;
}
```

### 6.2 性能对比测试

```java
package com.rpc.core.serialize;

import com.rpc.example.api.model.User;
import com.rpc.core.serialize.impl.JavaSerializer;
import com.rpc.core.serialize.impl.JsonSerializer;
import com.rpc.core.serialize.impl.KryoSerializer;

public class SerializerPerformanceTest {
    
    public static void main(String[] args) {
        User user = new User("张三", 25, "zhangsan@example.com");
        int iterations = 10000;
        
        System.out.println("=== 序列化性能测试 ===\n");
        
        // 测试 Kryo
        testSerializer("Kryo", new KryoSerializer(), user, iterations);
        
        // 测试 JSON
        testSerializer("JSON", new JsonSerializer(), user, iterations);
        
        // 测试 Java 原生
        testSerializer("Java 原生", new JavaSerializer(), user, iterations);
    }
    
    private static void testSerializer(String name, Serializer serializer, 
                                       User user, int iterations) {
        try {
            // 预热
            for (int i = 0; i < 100; i++) {
                byte[] bytes = serializer.serialize(user);
                serializer.deserialize(bytes, User.class);
            }
            
            // 正式测试
            long startSerialize = System.currentTimeMillis();
            byte[] bytes = null;
            for (int i = 0; i < iterations; i++) {
                bytes = serializer.serialize(user);
            }
            long endSerialize = System.currentTimeMillis();
            
            long startDeserialize = System.currentTimeMillis();
            for (int i = 0; i < iterations; i++) {
                serializer.deserialize(bytes, User.class);
            }
            long endDeserialize = System.currentTimeMillis();
            
            System.out.printf("%s:\n", name);
            System.out.printf("  序列化后大小：%d 字节\n", bytes.length);
            System.out.printf("  序列化耗时：%d ms\n", endSerialize - startSerialize);
            System.out.printf("  反序列化耗时：%d ms\n\n", 
                            endDeserialize - startDeserialize);
            
        } catch (Exception e) {
            System.err.println(name + " 测试失败：" + e.getMessage());
        }
    }
}
```

**典型输出结果**：
```
=== 序列化性能测试 ===

Kryo:
  序列化后大小：28 字节
  序列化耗时：45 ms
  反序列化耗时：52 ms

JSON:
  序列化后大小：48 字节
  序列化耗时：156 ms
  反序列化耗时：178 ms

Java 原生:
  序列化后大小：85 字节
  序列化耗时：312 ms
  反序列化耗时：425 ms
```

---

## 七、本课总结

### 核心知识点

1. **序列化的作用**
   - 将对象转换为可网络传输的字节流
   - 是 RPC 框架的核心组件之一

2. **常见序列化方案对比**
   - **Java 原生**：性能差，不推荐
   - **JSON**：可读性好，适合 RESTful API
   - **Protobuf**：性能最佳，适合跨语言
   - **Kryo**：性能优秀，使用简单，适合纯 Java 应用 ⭐
   - **Hessian**：跨语言，性能中等

3. **为什么选择 Kryo**
   - 性能好（仅次于 Protobuf）
   - 使用简单（无需预定义结构）
   - 学习成本低（适合教学）
   - 可扩展（设计了统一的序列化接口）

4. **实现要点**
   - 定义统一的序列化接口
   - 实现多种序列化器（Kryo、JSON、Java 原生）
   - 使用工厂模式管理序列化器
   - 使用 ThreadLocal 保证 Kryo 线程安全

### 课后思考

1. 为什么 Kryo 需要使用 ThreadLocal 封装？
2. Protobuf 性能这么好，为什么不用作本框架的默认序列化器？
3. 如何在不修改代码的情况下切换序列化方式？
4. 如果客户端和服务端使用不同的序列化器，会出现什么问题？如何解决？

---

## 八、动手练习

### 练习 1：实现 Hessian 序列化器

参考本章内容，实现一个 HessianSerializer：
```xml
<dependency>
    <groupId>com.caucho</groupId>
    <artifactId>hessian</artifactId>
    <version>4.0.66</version>
</dependency>
```

### 练习 2：添加序列化器配置

实现一个配置文件或注解，让用户可以通过配置选择默认的序列化器：
```properties
rpc.serializer.type=kryo  # 可选：kryo, json, hessian, java
```

### 练习 3：支持 null 值序列化

修改 KryoSerializer，使其能够正确处理 null 值。

---

## 九、下一步

下一节课我们将学习**网络通信基础**，深入了解 Netty 框架。

**[跳转到第 4 课：网络通信基础](./lesson-04-netty-basics.md)**

---

## 附录：序列化器类型标识

在 RPC 协议中，我们需要一个字节来标识使用的序列化器类型：

```java
public interface SerializerTypes {
    byte KRYO = 1;
    byte JSON = 2;
    byte JAVA = 3;
    byte HESSIAN = 4;
    byte PROTOBUF = 5;
}
```

这样在协议头中用一个字节就可以告诉对方使用哪种序列化器进行解析。
