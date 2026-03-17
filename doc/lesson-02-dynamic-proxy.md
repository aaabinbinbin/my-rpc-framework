# 第 2 课：动态代理 - RPC 客户端的核心

## 学习目标

- 理解动态代理在 RPC 中的作用
- 掌握 JDK 动态代理的使用
- 掌握 CGLIB 动态代理的使用
- 实现一个简单的 RPC 代理

---

## 一、为什么需要动态代理？

### 1.1 场景分析

想象一下，如果没有动态代理，我们的 RPC 客户端调用会是什么样？

```java
// 没有动态代理之前
RpcClient client = new RpcClient();
HelloRequest request = new HelloRequest();
request.setServiceName("HelloService");
request.setMethodName("sayHello");
request.setParameterTypes(new Class[]{String.class});
request.setArgs(new Object[]{"world"});

// 手动封装请求
byte[] data = serializer.serialize(request);

// 发送网络请求
byte[] response = client.send(data);

// 解析响应
HelloResponse helloResponse = serializer.deserialize(response, HelloResponse.class);
String result = (String) helloResponse.getResult();

System.out.println(result);
```

**问题**：
1. ❌ 代码重复：每次调用都要写一遍相同的样板代码
2. ❌ 容易出错：参数封装、异常处理等容易遗漏
3. ❌ 不直观：调用者需要关心底层细节

### 1.2 有了动态代理之后

```java
// 使用动态代理
HelloService service = RpcProxy.create(HelloService.class);
String result = service.sayHello("world");  // 就这么简单！
```

**优势**：
1. ✅ **简洁优雅**：像调用本地方法一样
2. ✅ **代码复用**：代理统一处理所有远程调用逻辑
3. ✅ **隐藏细节**：调用者不需要关心网络通信、序列化等底层实现

---

## 二、Java 动态代理详解

### 2.1 静态代理 vs 动态代理

#### （1）静态代理

在编译时就创建好代理类。

```java
// 接口
interface HelloService {
    String sayHello(String name);
}

// 真实实现
class HelloServiceImpl implements HelloService {
    public String sayHello(String name) {
        return "Hello, " + name;
    }
}

// 代理类（编译时就已经存在）
class HelloServiceProxy implements HelloService {
    private HelloService target;
    
    public HelloServiceProxy(HelloService target) {
        this.target = target;
    }
    
    @Override
    public String sayHello(String name) {
        System.out.println("[RPC] 开始远程调用...");
        // 这里可以添加日志、权限控制等逻辑
        return target.sayHello(name);
    }
}

// 使用
HelloService target = new HelloServiceImpl();
HelloService proxy = new HelloServiceProxy(target);
proxy.sayHello("world");
```

**缺点**：
- 一个代理类只能代理一种接口
- 如果接口增加方法，代理类也要修改
- 不够灵活

#### （2）动态代理 ⭐

在运行时动态生成代理类。

**优点**：
- 一个代理类可以代理任意实现了相同接口的类
- 运行时动态生成，灵活性高
- 非常适合框架开发

### 2.2 JDK 动态代理

JDK 自带的动态代理，位于 `java.lang.reflect` 包中。

#### 核心类介绍

**Proxy**：用于创建代理对象  
**InvocationHandler**：拦截方法调用的处理器

#### 示例代码

```java
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

// 1. 定义接口
interface HelloService {
    String sayHello(String name);
    String sayHi(String name);
}

// 2. 创建 InvocationHandler
class RpcInvocationHandler implements InvocationHandler {
    
    private Class<?> serviceClass;
    
    public RpcInvocationHandler(Class<?> serviceClass) {
        this.serviceClass = serviceClass;
    }
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 这里是拦截所有方法调用的地方
        // 可以在这里实现远程调用逻辑
        
        System.out.println("[RPC] 准备调用：" + serviceClass.getName() 
            + "." + method.getName());
        System.out.println("[RPC] 参数：" + Arrays.toString(args));
        
        // TODO: 后续在这里实现真正的远程调用
        // 现在只是模拟
        return "Mock Result for " + method.getName();
    }
}

// 3. 创建代理对象
public class JdkProxyExample {
    public static void main(String[] args) {
        // 创建代理
        HelloService proxy = (HelloService) Proxy.newProxyInstance(
            HelloService.class.getClassLoader(),           // 类加载器
            new Class<?>[]{HelloService.class},            // 要代理的接口
            new RpcInvocationHandler(HelloService.class)   // 调用处理器
        );
        
        // 使用代理对象调用方法
        String result1 = proxy.sayHello("world");
        System.out.println("结果 1: " + result1);
        
        String result2 = proxy.sayHi("rpc");
        System.out.println("结果 2: " + result2);
    }
}
```

**输出**：
```
[RPC] 准备调用：com.rpc.HelloService.sayHello
[RPC] 参数：[world]
结果 1: Mock Result for sayHello
[RPC] 准备调用：com.rpc.HelloService.sayHi
[RPC] 参数：[rpc]
结果 2: Mock Result for sayHi
```

#### JDK 动态代理的特点

✅ **优点**：
- JDK 自带，无需额外依赖
- 实现简单，易于理解

❌ **局限性**：
- **只能代理接口**，不能代理类
- 如果目标对象没有接口，就无法使用 JDK 代理

---

### 2.3 CGLIB 动态代理

CGLIB（Code Generation Library）是一个强大的第三方库，可以在运行时动态生成类的子类。

#### Maven 依赖

```xml
<dependency>
    <groupId>cglib</groupId>
    <artifactId>cglib</artifactId>
    <version>3.3.0</version>
</dependency>
```

#### 示例代码

```java
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

// 1. 定义一个类（注意：不是接口）
class HelloService {
    public String sayHello(String name) {
        return "Hello, " + name;
    }
    
    public String sayHi(String name) {
        return "Hi, " + name;
    }
}

// 2. 创建 MethodInterceptor
class RpcMethodInterceptor implements MethodInterceptor {
    
    @Override
    public Object intercept(Object obj, Method method, Object[] args, 
                           MethodProxy proxy) throws Throwable {
        // 拦截方法调用
        System.out.println("[CGLIB] 准备调用：" + method.getName());
        System.out.println("[CGLIB] 参数：" + Arrays.toString(args));
        
        // 可以在这里实现远程调用
        // 或者调用原始方法：proxy.invokeSuper(obj, args)
        
        return "Mock Result for " + method.getName();
    }
}

// 3. 创建代理对象
public class CglibProxyExample {
    public static void main(String[] args) {
        // 创建 Enhancer（增强器）
        Enhancer enhancer = new Enhancer();
        
        // 设置父类
        enhancer.setSuperclass(HelloService.class);
        
        // 设置回调（拦截器）
        enhancer.setCallback(new RpcMethodInterceptor());
        
        // 创建代理对象
        HelloService proxy = (HelloService) enhancer.create();
        
        // 使用代理对象
        String result1 = proxy.sayHello("world");
        System.out.println("结果 1: " + result1);
        
        String result2 = proxy.sayHi("rpc");
        System.out.println("结果 2: " + result2);
    }
}
```

#### CGLIB 的特点

✅ **优点**：
- **可以代理类**，不需要接口
- 性能优秀（某些场景下比 JDK 代理更快）
- Spring AOP、Hibernate 等框架都在使用

❌ **局限性**：
- 需要引入第三方依赖
- **不能代理 final 修饰的类和方法**（因为是通过继承实现的）

---

### 2.4 JDK 代理 vs CGLIB 对比

| 特性 | JDK 动态代理 | CGLIB 动态代理 |
|------|-------------|---------------|
| **实现方式** | 实现接口 | 继承子类 |
| **是否需要接口** | 是 | 否 |
| **依赖** | JDK 自带 | 需要第三方库 |
| **性能** | 较好 | 优秀 |
| **限制** | 必须有接口 | 不能代理 final |
| **适用场景** | 有接口的情况 | 无接口或需要代理类 |

**Spring AOP 的选择策略**：
- 如果有接口，优先使用 JDK 代理
- 如果没有接口，使用 CGLIB 代理

---

## 三、实现 RPC 代理

### 3.1 设计思路

我们要实现一个通用的 RPC 代理，能够：
1. 代理任意服务接口
2. 拦截接口方法调用
3. 封装调用参数
4. 发送远程请求（后续实现）
5. 返回响应结果

### 3.2 实现步骤

#### 步骤 1：定义 RPC 请求对象

```java
package com.rpc.core.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RPC 请求对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 服务类名 */
    private String serviceName;
    
    /** 方法名 */
    private String methodName;
    
    /** 参数类型数组 */
    private Class<?>[] parameterTypes;
    
    /** 参数值数组 */
    private Object[] parameters;
    
    /** 返回值类型 */
    private Class<?> returnType;
    
    /** 请求 ID（用于匹配响应） */
    private String requestId;
}
```

#### 步骤 2：定义 RPC 响应对象

```java
package com.rpc.core.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RPC 响应对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcResponse implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 请求 ID（与请求对应） */
    private String requestId;
    
    /** 响应状态码 */
    private Integer code;
    
    /** 响应消息 */
    private String message;
    
    /** 响应数据 */
    private Object data;
    
    /** 创建成功响应 */
    public static RpcResponse success(Object data, String requestId) {
        RpcResponse response = new RpcResponse();
        response.setRequestId(requestId);
        response.setCode(200);
        response.setMessage("Success");
        response.setData(data);
        return response;
    }
    
    /** 创建失败响应 */
    public static RpcResponse fail(Integer code, String message, String requestId) {
        RpcResponse response = new RpcResponse();
        response.setRequestId(requestId);
        response.setCode(code);
        response.setMessage(message);
        return response;
    }
}
```

#### 步骤 3：实现 JDK 动态代理版本的 RPC 代理

```java
package com.rpc.core.proxy;

import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * RPC 代理工厂（JDK 动态代理版本）
 */
@Slf4j
public class RpcProxyFactory {
    
    /**
     * 创建代理对象
     * @param serviceClass 服务接口类
     * @return 代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(Class<T> serviceClass) {
        return (T) Proxy.newProxyInstance(
            serviceClass.getClassLoader(),
            new Class<?>[]{serviceClass},
            new RpcInvocationHandler(serviceClass)
        );
    }
    
    /**
     * RPC 调用处理器
     */
    @Slf4j
    private static class RpcInvocationHandler implements InvocationHandler {
        
        private final Class<?> serviceClass;
        
        public RpcInvocationHandler(Class<?> serviceClass) {
            this.serviceClass = serviceClass;
        }
        
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 1. 构建 RPC 请求
            RpcRequest request = RpcRequest.builder()
                .serviceName(serviceClass.getName())
                .methodName(method.getName())
                .parameterTypes(method.getParameterTypes())
                .parameters(args)
                .returnType(method.getReturnType())
                .requestId(UUID.randomUUID().toString())
                .build();
            
            log.info("[RPC] 准备调用：{}.{}", request.getServiceName(), 
                     request.getMethodName());
            log.debug("[RPC] 请求详情：{}", request);
            
            // 2. TODO: 发送远程请求（留待后续章节实现）
            // RpcResponse response = sendRemoteRequest(request);
            
            // 3. 暂时模拟响应
            RpcResponse response = mockResponse(request);
            
            // 4. 返回结果
            if (response.getCode() == 200) {
                return response.getData();
            } else {
                throw new RuntimeException("RPC 调用失败：" + response.getMessage());
            }
        }
        
        /**
         * 模拟响应（用于测试）
         */
        private RpcResponse mockResponse(RpcRequest request) {
            // 这里只是临时模拟，后续会实现真正的远程调用
            String mockResult = "Mock result for " + request.getMethodName() 
                              + " with params: ";
            if (request.getParameters() != null) {
                for (Object param : request.getParameters()) {
                    mockResult += param + ", ";
                }
            }
            
            return RpcResponse.success(mockResult, request.getRequestId());
        }
    }
}
```

#### 步骤 4：测试代理

```java
package com.rpc.example.api;

/**
 * 测试接口
 */
public interface HelloService {
    String sayHello(String name);
    String sayHi(String name);
    Integer add(Integer a, Integer b);
}
```

```java
package com.rpc.core.proxy;

import com.rpc.example.api.HelloService;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 代理测试
 */
@Slf4j
public class RpcProxyTest {
    
    public static void main(String[] args) {
        // 1. 创建代理对象
        HelloService service = RpcProxyFactory.createProxy(HelloService.class);
        
        // 2. 调用方法
        String result1 = service.sayHello("world");
        log.info("调用结果 1: {}", result1);
        
        String result2 = service.sayHi("rpc framework");
        log.info("调用结果 2: {}", result2);
        
        Integer result3 = service.add(10, 20);
        log.info("调用结果 3: {}", result3);
    }
}
```

**输出**：
```
[main] INFO  c.r.c.p.RpcInvocationHandler - [RPC] 准备调用：com.rpc.example.api.HelloService.sayHello
[main] INFO  c.r.c.p.RpcProxyTest - 调用结果 1: Mock result for sayHello with params: world, 
[main] INFO  c.r.c.p.RpcInvocationHandler - [RPC] 准备调用：com.rpc.example.api.HelloService.sayHi
[main] INFO  c.r.c.p.RpcProxyTest - 调用结果 2: Mock result for sayHi with params: rpc framework, 
[main] INFO  c.r.c.p.RpcInvocationHandler - [RPC] 准备调用：com.rpc.example.api.HelloService.add
[main] INFO  c.r.c.p.RpcProxyTest - 调用结果 3: Mock result for add with params: 10, 20, 
```

---

## 四、知识扩展：反射与动态代理

### 4.1 反射基础

动态代理的核心是反射机制。

**反射的三大步骤**：

```java
// 1. 获取 Class 对象
Class<?> clazz = Class.forName("com.rpc.HelloServiceImpl");
// 或者
Class<?> clazz = HelloServiceImpl.class;
// 或者
Class<?> clazz = object.getClass();

// 2. 创建实例
Object instance = clazz.newInstance();  // 旧方式
Object instance = clazz.getDeclaredConstructor().newInstance();  // 推荐

// 3. 调用方法
Method method = clazz.getMethod("sayHello", String.class);
Object result = method.invoke(instance, "world");
```

### 4.2 方法参数的反射处理

```java
Method method = clazz.getMethod("methodName", paramTypes);

// 获取方法注解
Annotation[] annotations = method.getAnnotations();

// 获取泛型参数类型
Type[] genericParameterTypes = method.getGenericParameterTypes();

// 获取返回值泛型类型
Type genericReturnType = method.getGenericReturnType();
```

---

## 五、本课总结

### 核心知识点

1. **动态代理的作用**
   - 隐藏远程调用的复杂性
   - 提供统一的调用入口
   - 便于添加横切逻辑（日志、监控、熔断等）

2. **JDK 动态代理**
   - 基于接口实现
   - 使用 `Proxy` 和 `InvocationHandler`
   - JDK 自带，无需额外依赖

3. **CGLIB 动态代理**
   - 基于类继承实现
   - 使用 `Enhancer` 和 `MethodInterceptor`
   - 可以代理类，但需要第三方库

4. **RPC 代理实现**
   - 定义 RPC 请求和响应对象
   - 使用动态代理拦截方法调用
   - 封装调用参数为 RPC 请求
   - 返回响应结果（目前是模拟）

### 课后思考

1. JDK 动态代理和 CGLIB 动态代理的本质区别是什么？
2. 为什么 final 修饰的类和方法不能被 CGLIB 代理？
3. 如何在代理中添加超时控制逻辑？
4. 如果一个接口有多个方法，如何区分调用的是哪个方法？

---

## 六、动手练习

### 练习 1：实现 CGLIB 版本的 RPC 代理

参考本章内容，实现一个 CGLIB 版本的 RpcProxyFactory，要求：
- 可以代理没有接口的类
- 同样能够封装 RPC 请求

**实现提示**：
1. 使用 `Enhancer` 创建代理对象
2. 实现 `MethodInterceptor` 接口拦截方法调用
3. 在 `intercept` 方法中构建 `RpcRequest`
4. 注意：CGLIB 是通过继承实现的，所以不能代理 final 类和方法

**参考代码结构**：
```java
public class RpcProxyFactory {
    
    // JDK 动态代理版本（已实现）
    public static <T> T createProxy(Class<T> interfaceClass) {
        // ...
    }
    
    // CGLIB 版本（需要你实现）
    public static <T> T createProxyByCGLib(Class<T> classToProxy) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(classToProxy);
        enhancer.setCallback(new RpcMethodInterceptor());
        return (T) enhancer.create();
    }
    
    private static class RpcMethodInterceptor implements MethodInterceptor {
        @Override
        public Object intercept(Object obj, Method method, Object[] args, 
                               MethodProxy proxy) throws Throwable {
            // 1. 构建 RPC 请求
            RpcRequest request = RpcRequest.builder()
                .serviceName(obj.getClass().getName())
                .methodName(method.getName())
                .parameterTypes(method.getParameterTypes())
                .parameters(args)
                .returnType(method.getReturnType())
                .requestId(UUID.randomUUID().toString())
                .build();
            
            // 2. 发送远程请求（目前先模拟）
            RpcResponse response = mockResponse(request);
            
            // 3. 返回结果
            return response.getData();
        }
    }
}
```

---

### 练习 2：为代理添加超时控制

在 RpcInvocationHandler 中添加超时控制：
```java
// 期望的效果
HelloService service = RpcProxyFactory.createProxy(
    HelloService.class, 
    5000  // 5 秒超时
);
```

**实现提示**：
1. 修改 `createProxy` 方法，增加超时参数
2. 在 `InvocationHandler` 中使用 `Future` 和 `ExecutorService` 实现超时控制
3. 使用 `future.get(timeout, TimeUnit)` 设置超时
4. 如果超时，抛出 `TimeoutException`

**参考代码结构**：
```java
public class RpcProxyFactory {
    
    // 带超时的代理创建方法
    public static <T> T createProxy(Class<T> interfaceClass, long timeoutMillis) {
        return (T) Proxy.newProxyInstance(
            interfaceClass.getClassLoader(),
            new Class<?>[]{interfaceClass},
            new RpcInvocationHandler(interfaceClass, timeoutMillis)
        );
    }
    
    private static class RpcInvocationHandler implements InvocationHandler {
        
        private final Class<?> serviceClass;
        private final long timeoutMillis;  // 超时时间
        
        public RpcInvocationHandler(Class<?> serviceClass, long timeoutMillis) {
            this.serviceClass = serviceClass;
            this.timeoutMillis = timeoutMillis;
        }
        
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 1. 构建 RPC 请求
            RpcRequest request = buildRequest(method, args);
            
            // 2. 使用线程池执行远程调用，并设置超时
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Object> future = executor.submit(() -> {
                    RpcResponse response = sendRemoteRequest(request);
                    return response.getData();
                });
                
                // 设置超时
                return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new RuntimeException("RPC 调用超时（超过 " + timeoutMillis + "ms）", e);
            } finally {
                executor.shutdown();
            }
        }
    }
}
```

**思考题**：
- 每次调用都创建新的线程池是否合理？如何优化？
- 超时时间应该全局统一配置，还是每个服务单独配置？

---

### 练习 3：为代理添加重试机制

当远程调用失败时，自动重试 3 次。

**实现提示**：
1. 在 `InvocationHandler` 中添加重试次数参数
2. 使用循环实现重试逻辑
3. 记录重试次数，超过最大重试次数后抛出异常
4. 可以在每次重试之间添加短暂延迟

**参考代码结构**：
```java
public class RpcProxyFactory {
    
    // 带重试的代理创建方法
    public static <T> T createProxyWithRetry(Class<T> interfaceClass, int maxRetries) {
        return (T) Proxy.newProxyInstance(
            interfaceClass.getClassLoader(),
            new Class<?>[]{interfaceClass},
            new RpcInvocationHandler(interfaceClass, maxRetries)
        );
    }
    
    private static class RpcInvocationHandler implements InvocationHandler {
        
        private final Class<?> serviceClass;
        private final int maxRetries;  // 最大重试次数
        
        public RpcInvocationHandler(Class<?> serviceClass, int maxRetries) {
            this.serviceClass = serviceClass;
            this.maxRetries = maxRetries;
        }
        
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            RpcRequest request = buildRequest(method, args);
            
            int retryCount = 0;
            Exception lastException = null;
            
            // 重试逻辑
            while (retryCount <= maxRetries) {
                try {
                    log.info("[RPC] 第 {} 次尝试调用：{}.{}", 
                             retryCount + 1, serviceClass.getName(), method.getName());
                    
                    RpcResponse response = sendRemoteRequest(request);
                    
                    if (response.getCode() == 200) {
                        return response.getData();
                    } else {
                        throw new RuntimeException("RPC 调用失败：" + response.getMessage());
                    }
                } catch (Exception e) {
                    lastException = e;
                    retryCount++;
                    
                    if (retryCount > maxRetries) {
                        break;
                    }
                    
                    // 可选：在重试前等待一小段时间（退避策略）
                    Thread.sleep(100 * retryCount);
                }
            }
            
            throw new RuntimeException("RPC 调用失败，已重试 " + maxRetries + " 次", lastException);
        }
    }
}
```

**进阶挑战**：
- 实现指数退避策略（第一次等 100ms，第二次等 200ms，第三次等 400ms...）
- 只对特定类型的异常进行重试（如网络超时），对业务异常不重试
- 结合超时控制和重试机制，创建一个功能完整的代理工厂

---

## 七、下一步

下一节课我们将学习**序列化协议**，这是 RPC 通信的关键环节。

**[跳转到第 3 课：序列化协议](./lesson-03-serialization.md)**

---

## 附录：代码位置

本课涉及的代码文件：
- `RpcRequest.java` - RPC 请求对象
- `RpcResponse.java` - RPC 响应对象
- `RpcProxyFactory.java` - RPC 代理工厂
- `HelloService.java` - 测试接口
- `RpcProxyTest.java` - 代理测试类

这些代码将在后续课程中不断完善。
