# 第 5 课：设计 RPC 通信协议

## 学习目标

- 理解自定义协议在 RPC 中的重要性
- 掌握 RPC 协议的设计原则
- 学会设计消息头和消息体
- 解决粘包和拆包问题

---

## 一、为什么需要自定义协议？

### 1.1 什么是协议？

**协议**就是通信双方约定好的数据格式规则。

**生活中的例子**：
```
写信的格式：
┌─────────────────────────────────┐
│  收件人地址：xxx                │
│  收件人姓名：xxx                │
│  ─────────────────────────────  │
│  正文内容：                     │
│  xxxxxxxxxxxx                   │
│  ─────────────────────────────  │
│  寄件人：xxx                    │
│  日期：2024-01-01               │
└─────────────────────────────────┘
```

如果没有统一的格式，邮局就无法知道哪里是收件人地址，哪里是正文内容。

### 1.2 RPC 为什么需要自定义协议？

#### 场景分析

假设我们有一个简单的 RPC 调用：

```java
// 客户端调用
HelloService service = proxy.create(HelloService.class);
String result = service.sayHello("world");
```

这个调用需要传输哪些信息？

**必须包含的信息**：
1. ✅ 服务类名：`com.rpc.example.HelloService`
2. ✅ 方法名：`sayHello`
3. ✅ 参数类型：`[String]`
4. ✅ 参数值：`["world"]`
5. ✅ 返回值类型：`String`
6. ✅ 请求 ID（用于匹配响应）

#### 如果不用自定义协议会怎样？

**错误示例 - 直接发送字符串**：
```java
// ❌ 错误的做法
String message = "HelloService|sayHello|String|world";
channel.writeAndFlush(message);
```

**问题**：
1. ❌ **无法处理复杂参数**：如果参数本身包含 `|` 怎么办？
2. ❌ **没有类型信息**：服务端不知道如何反序列化
3. ❌ **没有错误处理**：失败了怎么办？
4. ❌ **没有版本控制**：协议升级怎么办？
5. ❌ **不安全**：没有校验机制

### 1.3 自定义协议的优势

✅ **结构化**：清晰的数据格式，易于解析  
✅ **高效**：二进制协议，体积小，性能好  
✅ **可扩展**：可以添加版本、校验等信息  
✅ **安全**：可以添加签名、加密等机制  
✅ **可靠**：包含状态码、错误信息等  

---

## 二、RPC 协议设计原则

### 2.1 设计原则

#### （1）简洁性
- 协议头尽量短小精悍
- 减少不必要的字段
- 降低网络传输开销

#### （2）高效性
- 使用二进制格式（而非文本）
- 选择高性能的序列化方式
- 减少序列化的次数

#### （3）可扩展性
- 预留扩展字段
- 支持版本控制
- 便于未来功能升级

#### （4）可靠性
- 包含校验和（Magic Number）
- 包含状态码
- 包含错误信息

#### （5）兼容性
- 支持协议版本升级
- 向前兼容旧版本
- 支持多种序列化方式

---

## 三、设计我们的 RPC 协议

### 3.1 协议格式设计

我们的 RPC 协议分为两部分：**消息头（Header）** + **消息体（Body）**

```
┌─────────────────────────────────────────────────────────┐
│                      RPC Message                        │
├─────────────────────────────────────────────────────────┤
│                    Header (固定长度)                     │
├─────────────────────────────────────────────────────────┤
│                     Body (变长)                         │
└─────────────────────────────────────────────────────────┘
```

### 3.2 消息头设计（Header）

消息头包含协议的元数据，固定长度为 **16 字节**：

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          Magic Number                         |
+---------------------------------------------------------------+
|   Version     |   Serializer  |    MessageType   |  Reserved  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Request ID                            |
|                       (8 bytes)                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Body Length                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

#### 各字段说明

| 字段 | 长度 | 说明 | 示例 |
|------|------|------|------|
| **Magic Number** | 4 字节 | 魔数，用于校验数据包 | `0x12345678` |
| **Version** | 1 字节 | 协议版本号 | `1` |
| **Serializer** | 1 字节 | 序列化器类型 | `1=Kryo, 2=JSON` |
| **MessageType** | 1 字节 | 消息类型 | `1=请求，2=响应` |
| **Reserved** | 1 字节 | 保留字段（备用） | `0` |
| **Request ID** | 8 字节 | 请求唯一标识 | `UUID 生成` |
| **Body Length** | 4 字节 | 消息体长度 | `1024` |

**总长度**：4 + 1 + 1 + 1 + 1 + 8 + 4 = **20 字节**

**修正**：让我们重新设计为 16 字节：

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          Magic Number                         |
+---------------------------------------------------------------+
|   Version     |   Serializer  |    MessageType   |  Reserved  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Body Length                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Request ID                            |
|                       (8 bytes)                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**实际长度**：4 + 4 + 4 + 8 = **20 字节**

为了对齐和实现简单，我们就用 **20 字节**作为消息头长度。

### 3.3 消息体设计（Body）

消息体包含实际的业务数据，长度可变。

#### 请求体（Request Body）

```java
RpcRequest {
    String serviceName;      // 服务类名
    String methodName;       // 方法名
    Class<?>[] parameterTypes;  // 参数类型数组
    Object[] parameters;     // 参数值数组
    Class<?> returnType;     // 返回值类型
}
```

#### 响应体（Response Body）

```java
RpcResponse {
    Integer code;            // 状态码
    String message;          // 响应消息
    Object data;             // 响应数据
}
```

---

## 四、实现协议数据结构

### 4.1 定义消息头

```java
package com.rpc.core.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RPC 协议消息头
 * 固定长度 20 字节
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcHeader {
    
    /** 魔数，4 字节 */
    private int magicNumber = 0x12345678;
    
    /** 版本号，1 字节 */
    private byte version = 1;
    
    /** 序列化器类型，1 字节 */
    private byte serializerType;
    
    /** 消息类型，1 字节 */
    private byte messageType;
    
    /** 保留字段，1 字节 */
    private byte reserved;
    
    /** 请求 ID，8 字节 */
    private long requestId;
    
    /** 消息体长度，4 字节 */
    private int bodyLength;
    
    /** 消息头总长度：20 字节 */
    public static final int HEADER_LENGTH = 20;
    
    /** 魔数常量 */
    public static final int MAGIC_NUMBER = 0x12345678;
    
    /** 协议版本 */
    public static final byte VERSION = 1;
}
```

### 4.2 定义消息类型

```java
package com.rpc.core.protocol;

/**
 * 消息类型枚举
 */
public interface MessageType {
    
    /** 请求消息 */
    byte REQUEST = 1;
    
    /** 响应消息 */
    byte RESPONSE = 2;
    
    /** 心跳请求 */
    byte HEARTBEAT_REQUEST = 3;
    
    /** 心跳响应 */
    byte HEARTBEAT_RESPONSE = 4;
}
```

### 4.3 完整的 RPC 消息

```java
package com.rpc.core.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RPC 完整消息（消息头 + 消息体）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcMessage {
    
    /** 消息头 */
    private RpcHeader header;
    
    /** 消息体（可能是 RpcRequest 或 RpcResponse） */
    private Object body;
}
```

---

## 五、解决粘包/拆包问题

### 5.1 什么是粘包/拆包？

#### 粘包（Stick）

客户端发送两个独立的数据包：
```
[包 1: Hello][包 2: World]
```

服务端收到的是：
```
[HelloWorld] ← 粘在一起了！
```

#### 拆包（Package Breaking）

客户端发送一个数据包：
```
[Hello World!]
```

服务端收到的是：
```
[Hello ][World!] ← 被拆开了！
```

### 5.2 为什么会粘包/拆包？

TCP 是**流式协议**，没有边界概念。

```
应用程序                    TCP 缓冲区                    接收方
    ↓                          ↓                            ↓
发送 [包 A]  ───────────→  [包 A]                     
发送 [包 B]  ───────────→         [包 B]                  
                                   ↓                        
                            一次性读取                       
                                   ↓                        
                          [包 A][包 B] ← 粘包！
```

**原因**：
1. **Nagle 算法**：TCP 为了优化性能，会合并多个小包
2. **延迟确认**：接收方可能延迟发送 ACK
3. **缓冲区大小**：读取的数据量不等于发送的数据量

### 5.3 解决方案

#### 方案 1：固定长度

每个消息固定长度，例如都是 1024 字节。

```java
// 编码器：填充到固定长度
byte[] data = new byte[1024];
System.arraycopy(original, 0, data, 0, original.length);

// 解码器：每次读取固定长度
byte[] buffer = new byte[1024];
channel.read(buffer);
```

**缺点**：浪费空间，不够灵活。

#### 方案 2：特殊分隔符

使用特殊字符分隔消息，如 `\r\n`。

```java
// 基于换行符的解码器
pipeline.addLast(new LineBasedFrameDecoder(1024));
pipeline.addLast(new StringDecoder());
```

**缺点**：只适合文本协议，不适合二进制。

#### 方案 3：长度字段 ⭐ 我们采用这种

在消息头中包含长度字段，告诉接收方消息有多长。

```
┌──────────────┬────────────────┐
│ Body Length  │     Body       │
│  (4 字节)    │   (变长)       │
└──────────────┴────────────────┘
       ↓
读取时先读 4 字节获取长度
       ↓
再根据长度读取消息体
```

Netty 提供了现成的解码器：`LengthFieldBasedFrameDecoder`

---

## 六、Netty 编解码器详解

### 6.1 什么是编解码器？

**编码器（Encoder）**：将 Java 对象转换为字节流的过程  
**解码器（Decoder）**：将字节流转换为 Java 对象的过程

```
编码过程：Java 对象 → 序列化 → 字节流 → 网络传输
解码过程：字节流 → 反序列化 → Java 对象 → 业务处理
```

### 6.2 Netty 提供的常用编解码器

#### （1）内置编解码器

**字符串编解码**：
```java
// 字符串编码器：String → byte[]
pipeline.addLast(new StringEncoder());

// 字符串解码器：byte[] → String
pipeline.addLast(new StringDecoder());
```

**对象编解码**：
```java
// Java 对象序列化编码器
pipeline.addLast(new ObjectEncoder());

// Java 对象反序列化解码器
pipeline.addLast(new ObjectDecoder());
```

**行编解码器**（基于换行符）：
```java
// 按行读取，最大 1024 字节
pipeline.addLast(new LineBasedFrameDecoder(1024));
pipeline.addLast(new StringDecoder());
```

#### （2）长度字段编解码器 ⭐

**LengthFieldBasedFrameDecoder**：根据长度字段自动拆包/粘包

```java
/**
 * 构造参数说明：
 * @param maxFrameLength      最大帧长度（超过则丢弃）
 * @param lengthFieldOffset   长度字段偏移量（从第几个字节开始）
 * @param lengthFieldLength   长度字段占用字节数
 * @param lengthAdjustment    长度调整值（通常是负数，表示长度字段之后的内容）
 * @param initialBytesToStrip 跳过的字节数（通常用于跳过消息头）
 */
public RpcProtocolDecoder() {
    super(
        1024 * 1024,    // 最大 1MB
        16,             // 长度字段在 header 中的偏移（第 17 字节开始）
        4,              // 长度字段占用 4 字节
        0,              // 不需要调整
        0               // 不跳过任何字节
    );
}
```

**参数详解图**：
```
消息头（20 字节）
┌────────────────────────────────────────┐
│ Magic(4) │ Ver(1) │ ... │ Reserved(1) │
├────────────────────────────────────────┤
│ RequestID(8) │ BodyLength(4) ← 偏移 16 │
└────────────────────────────────────────┘
         ↓
   lengthFieldOffset = 16
   lengthFieldLength = 4
```

### 6.3 自定义编码器

**继承 MessageToByteEncoder**：

```java
@Slf4j
public class RpcProtocolEncoder extends MessageToByteEncoder<RpcMessage> {
    
    @Override
    protected void encode(ChannelHandlerContext ctx, RpcMessage msg, 
                         ByteBuf out) throws Exception {
        
        RpcHeader header = msg.getHeader();
        Object body = msg.getBody();
        
        // 1. 获取序列化器
        Serializer serializer = SerializerFactory.getSerializer(
            header.getSerializerType()
        );
        
        // 2. 序列化消息体
        byte[] bodyBytes = serializer.serialize(body);
        
        // 3. 更新消息头
        header.setBodyLength(bodyBytes.length);
        
        // 4. 写入消息头（20 字节）
        out.writeInt(header.getMagicNumber());           // 4 字节
        out.writeByte(header.getVersion());              // 1 字节
        out.writeByte(header.getSerializerType());       // 1 字节
        out.writeByte(header.getMessageType());          // 1 字节
        out.writeByte(header.getReserved());             // 1 字节
        out.writeLong(header.getRequestId());            // 8 字节
        out.writeInt(header.getBodyLength());            // 4 字节
        
        // 5. 写入消息体
        out.writeBytes(bodyBytes);
        
        log.debug("编码完成：requestId={}, bodyLength={}", 
                 header.getRequestId(), header.getBodyLength());
    }
}
```

**执行流程**：
```
RpcMessage
    ↓
encode() 方法被调用
    ↓
写入消息头到 ByteBuf
    ↓
写入消息体到 ByteBuf
    ↓
自动发送到 ChannelPipeline
```

### 6.4 自定义解码器

**继承 LengthFieldBasedFrameDecoder**：

```java
@Slf4j
public class RpcProtocolDecoder extends LengthFieldBasedFrameDecoder {
    
    public RpcProtocolDecoder() {
        super(
            1024 * 1024,    // 最大 1MB
            16,             // 长度字段在 header 中的偏移
            4,              // 长度字段占用 4 字节
            0,              // 不需要调整
            0               // 不跳过任何字节
        );
    }
    
    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) 
            throws Exception {
        
        // 1. 调用父类方法，获取完整的帧
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null;  // 数据不完整，等待更多数据
        }
        
        // 2. 读取消息头
        RpcHeader header = new RpcHeader();
        header.setMagicNumber(frame.readInt());          // 4 字节
        header.setVersion(frame.readByte());             // 1 字节
        header.setSerializerType(frame.readByte());      // 1 字节
        header.setMessageType(frame.readByte());         // 1 字节
        header.setReserved(frame.readByte());            // 1 字节
        header.setRequestId(frame.readLong());           // 8 字节
        header.setBodyLength(frame.readInt());           // 4 字节
        
        // 3. 校验魔数
        if (header.getMagicNumber() != RpcHeader.MAGIC_NUMBER) {
            log.error("魔数校验失败：{}", header.getMagicNumber());
            frame.release();
            throw new IllegalArgumentException("非法的 RPC 消息，魔数不匹配");
        }
        
        // 4. 读取消息体
        byte[] bodyBytes = new byte[header.getBodyLength()];
        frame.readBytes(bodyBytes);
        
        // 5. 反序列化消息体
        Serializer serializer = SerializerFactory.getSerializer(
            header.getSerializerType()
        );
        
        Object body;
        if (header.getMessageType() == 1) {  // 请求
n            body = serializer.deserialize(bodyBytes, Object.class);
        } else {  // 响应
            body = serializer.deserialize(bodyBytes, Object.class);
        }
        
        frame.release();
        
        // 6. 构建 RpcMessage
        RpcMessage message = new RpcMessage();
        message.setHeader(header);
        message.setBody(body);
        
        log.debug("解码完成：requestId={}, bodyLength={}", 
                 header.getRequestId(), header.getBodyLength());
        
        return message;
    }
}
```

**执行流程**：
```
字节流进入
    ↓
LengthFieldBasedFrameDecoder 自动处理粘包/拆包
    ↓
decode() 方法被调用
    ↓
读取并校验消息头
    ↓
读取消息体并反序列化
    ↓
返回 RpcMessage 对象
```

### 6.5 编解码器在 Pipeline 中的位置

**服务端 Pipeline**：
```java
ch.pipeline()
  // 入站处理器（从外向内）
  .addLast("idleStateHandler", new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS))
  .addLast("decoder", new RpcProtocolDecoder())      // ← 解码器
  .addLast("handler", new RpcRequestHandler())       // ← 业务处理器
  // 出站处理器（从内向外）
  .addLast("encoder", new RpcProtocolEncoder());     // ← 编码器
```

**客户端 Pipeline**：
```java
ch.pipeline()
  .addLast("decoder", new RpcProtocolDecoder())      // ← 解码器
  .addLast("encoder", new RpcProtocolEncoder())      // ← 编码器
  .addLast("handler", new RpcClientHandler());       // ← 业务处理器
```

**数据流向图**：
```
服务端接收数据：
网络 → Decoder → Handler → 业务逻辑
              ↑
           只处理 RpcMessage

服务端发送数据：
业务逻辑 → Encoder → 网络
            ↑
         接收 RpcMessage
```

### 6.6 测试编解码器

**使用 EmbeddedChannel 进行单元测试**：

```java
@Test
public void testEncodeDecode() {
    // 1. 创建测试数据
    RpcRequest request = new RpcRequest();
    request.setServiceName("com.rpc.HelloService");
    request.setMethodName("sayHello");
    request.setParameterTypes(new Class[]{String.class});
    request.setParameters(new Object[]{"world"});
    
    // 2. 创建消息
    RpcHeader header = RpcHeader.builder()
        .requestId(123456L)
        .serializerType((byte) 1)  // Kryo
        .messageType((byte) 1)      // 请求
        .build();
    
    RpcMessage message = new RpcMessage();
    message.setHeader(header);
    message.setBody(request);
    
    // 3. 创建编码通道（模拟出站）
    EmbeddedChannel encoderChannel = new EmbeddedChannel(
        new RpcProtocolEncoder()
    );
    
    // 4. 编码并读取字节
    assertTrue(encoderChannel.writeOutbound(message));
    ByteBuf encoded = (ByteBuf) encoderChannel.readOutbound();
    
    // 5. 创建解码通道（模拟入站）
    EmbeddedChannel decoderChannel = new EmbeddedChannel(
        new RpcProtocolDecoder()
    );
    
    // 6. 解码并验证
    assertTrue(decoderChannel.writeInbound(encoded));
    RpcMessage decoded = (RpcMessage) decoderChannel.readInbound();
    
    // 7. 断言验证
    assertNotNull(decoded);
    assertEquals(123456L, decoded.getHeader().getRequestId());
    assertEquals(1, decoded.getHeader().getMessageType());
    
    RpcRequest decodedRequest = (RpcRequest) decoded.getBody();
    assertEquals("com.rpc.HelloService", decodedRequest.getServiceName());
    assertEquals("sayHello", decodedRequest.getMethodName());
    
    // 8. 关闭通道
    encoderChannel.finish();
    decoderChannel.finish();
}
```

**EmbeddedChannel 说明**：
- 嵌入式的 Channel，用于单元测试
- 不需要真实的网络连接
- 可以模拟入站和出站数据流

---

## 七、实现 RPC 编解码器

### 7.1 RPC 协议编码器

```java
package com.rpc.core.protocol.codec;

import com.rpc.core.protocol.RpcHeader;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.serialize.Serializer;
import com.rpc.core.serialize.SerializerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 协议编码器
 * 将 RpcMessage 编码为字节流
 */
@Slf4j
public class RpcProtocolEncoder extends MessageToByteEncoder<RpcMessage> {
    
    @Override
    protected void encode(ChannelHandlerContext ctx, RpcMessage msg, 
                         ByteBuf out) throws Exception {
        
        RpcHeader header = msg.getHeader();
        Object body = msg.getBody();
        
        // 1. 获取序列化器
        Serializer serializer = SerializerFactory.getSerializer(
            header.getSerializerType()
        );
        
        // 2. 序列化消息体
        byte[] bodyBytes = serializer.serialize(body);
        
        // 3. 更新消息头
        header.setBodyLength(bodyBytes.length);
        
        // 4. 写入消息头（20 字节）
        out.writeInt(header.getMagicNumber());           // 4 字节
        out.writeByte(header.getVersion());              // 1 字节
        out.writeByte(header.getSerializerType());       // 1 字节
        out.writeByte(header.getMessageType());          // 1 字节
        out.writeByte(header.getReserved());             // 1 字节
        out.writeLong(header.getRequestId());            // 8 字节
        out.writeInt(header.getBodyLength());            // 4 字节
        
        // 5. 写入消息体
        out.writeBytes(bodyBytes);
        
        log.debug("编码完成：requestId={}, bodyLength={}", 
                 header.getRequestId(), header.getBodyLength());
    }
}
```

### 7.2 RPC 协议解码器

```java
package com.rpc.core.protocol.codec;

import com.rpc.core.protocol.RpcHeader;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.serialize.Serializer;
import com.rpc.core.serialize.SerializerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 协议解码器
 * 将字节流解码为 RpcMessage
 */
@Slf4j
public class RpcProtocolDecoder extends LengthFieldBasedFrameDecoder {
    
    /**
     * 构造参数说明：
     * maxFrameLength: 最大帧长度（1MB）
     * lengthFieldOffset: 长度字段偏移量（16 字节，从第 17 字节开始）
     * lengthFieldLength: 长度字段长度（4 字节）
     * lengthAdjustment: 长度调整值（0）
     * initialBytesToStrip: 跳过的字节数（0，不跳过）
     */
    public RpcProtocolDecoder() {
        super(
            1024 * 1024,    // 最大 1MB
            16,             // 长度字段在 header 中的偏移
            4,              // 长度字段占用 4 字节
            0,              // 不需要调整
            0               // 不跳过任何字节
        );
    }
    
    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) 
            throws Exception {
        
        // 1. 调用父类方法，获取完整的帧
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null;
        }
        
        // 2. 读取消息头
        RpcHeader header = new RpcHeader();
        header.setMagicNumber(frame.readInt());          // 4 字节
        header.setVersion(frame.readByte());             // 1 字节
        header.setSerializerType(frame.readByte());      // 1 字节
        header.setMessageType(frame.readByte());         // 1 字节
        header.setReserved(frame.readByte());            // 1 字节
        header.setRequestId(frame.readLong());           // 8 字节
        header.setBodyLength(frame.readInt());           // 4 字节
        
        // 3. 校验魔数
        if (header.getMagicNumber() != RpcHeader.MAGIC_NUMBER) {
            log.error("魔数校验失败：{}", header.getMagicNumber());
            frame.release();
            throw new IllegalArgumentException("非法的 RPC 消息，魔数不匹配");
        }
        
        // 4. 读取消息体
        byte[] bodyBytes = new byte[header.getBodyLength()];
        frame.readBytes(bodyBytes);
        
        // 5. 反序列化消息体
        Serializer serializer = SerializerFactory.getSerializer(
            header.getSerializerType()
        );
        
        Object body;
        if (header.getMessageType() == 1) {  // 请求
            body = serializer.deserialize(bodyBytes, Object.class);
        } else {  // 响应
            body = serializer.deserialize(bodyBytes, Object.class);
        }
        
        frame.release();
        
        // 6. 构建 RpcMessage
        RpcMessage message = new RpcMessage();
        message.setHeader(header);
        message.setBody(body);
        
        log.debug("解码完成：requestId={}, bodyLength={}", 
                 header.getRequestId(), header.getBodyLength());
        
        return message;
    }
}
```

---

## 八、测试编解码器

### 8.1 单元测试

```java
package com.rpc.core.protocol.codec;

import com.rpc.core.protocol.RpcHeader;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.protocol.impl.RpcRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.junit.Assert.*;

public class RpcProtocolCodecTest {
    
    @Test
    public void testEncodeDecode() {
        // 1. 创建测试数据
        RpcRequest request = new RpcRequest();
        request.setServiceName("com.rpc.HelloService");
        request.setMethodName("sayHello");
        request.setParameterTypes(new Class[]{String.class});
        request.setParameters(new Object[]{"world"});
        
        // 2. 创建消息
        RpcHeader header = RpcHeader.builder()
            .requestId(123456L)
            .serializerType((byte) 1)  // Kryo
            .messageType((byte) 1)      // 请求
            .build();
        
        RpcMessage message = new RpcMessage();
        message.setHeader(header);
        message.setBody(request);
        
        // 3. 创建编码通道
        EmbeddedChannel encoderChannel = new EmbeddedChannel(
            new RpcProtocolEncoder()
        );
        
        // 4. 编码
        assertTrue(encoderChannel.writeOutbound(message));
        ByteBuf encoded = (ByteBuf) encoderChannel.readOutbound();
        
        // 5. 创建解码通道
        EmbeddedChannel decoderChannel = new EmbeddedChannel(
            new RpcProtocolDecoder()
        );
        
        // 6. 解码
        assertTrue(decoderChannel.writeInbound(encoded));
        RpcMessage decoded = (RpcMessage) decoderChannel.readInbound();
        
        // 7. 验证
        assertNotNull(decoded);
        assertEquals(123456L, decoded.getHeader().getRequestId());
        assertEquals(1, decoded.getHeader().getMessageType());
        
        RpcRequest decodedRequest = (RpcRequest) decoded.getBody();
        assertEquals("com.rpc.HelloService", decodedRequest.getServiceName());
        assertEquals("sayHello", decodedRequest.getMethodName());
        
        // 8. 关闭通道
        encoderChannel.finish();
        decoderChannel.finish();
    }
}
```

---

## 九、本课总结

### 核心知识点

1. **自定义协议的重要性**
   - 提供结构化的数据格式
   - 提高传输效率
   - 增强可靠性和安全性

2. **协议设计原则**
   - 简洁性、高效性、可扩展性
   - 可靠性、兼容性

3. **我们的协议格式**
   - **消息头（20 字节）**：魔数 + 版本 + 序列化器 + 类型 + 请求 ID + 长度
   - **消息体（变长）**：实际的请求/响应数据

4. **粘包/拆包问题**
   - TCP 是流式协议，没有消息边界
   - 解决方案：固定长度、分隔符、长度字段
   - 我们采用**长度字段**方式

5. **Netty 编解码器**
   - **编码器**：继承 `MessageToByteEncoder<I>`，重写 `encode()` 方法
     - 将 Java 对象编码为字节流
     - 通过 `ByteBuf` 写入数据
     - 自动添加到 Pipeline 的出站方向
   
   - **解码器**：继承 `LengthFieldBasedFrameDecoder`
     - 自动解决 TCP 粘包/拆包问题
     - 重写 `decode()` 方法进行自定义解析
     - 支持魔数校验、版本检查等
     - 添加到 Pipeline 的入站方向
   
   - **工作原理**：
     ```
     入站数据流：字节流 → 解码器 → 业务 Handler
     出站数据流：业务 Handler → 编码器 → 字节流
     ```
   
   - **常用 API**：
     - `ctx.writeAndFlush(msg)`：写出数据
     - `frame.readXXX()`：从 ByteBuf 读取数据
     - `out.writeXXX()`：向 ByteBuf 写入数据
   
   - **测试方法**：使用 `EmbeddedChannel` 进行单元测试
     - 模拟入站/出站数据流
     - 无需真实网络连接
     - 验证编解码正确性

### 课后思考

1. 为什么要设计魔数？魔数有什么作用？
2. 消息头为什么要用固定长度？
3. 除了长度字段，还有哪些解决粘包的方法？
4. 如何在不中断服务的情况下升级协议版本？

---

## 十、动手练习参考答案

### 练习 1：添加协议版本检查

**题目**：修改解码器，当收到不支持的协议版本时，抛出异常并关闭连接。

**答案**：
```java
@Override
protected Object decode(ChannelHandlerContext ctx, ByteBuf in) 
        throws Exception {
    
    // 1. 调用父类方法，获取完整的帧
    ByteBuf frame = (ByteBuf) super.decode(ctx, in);
    if (frame == null) {
        return null;
    }
    
    // 2. 读取消息头
    RpcHeader header = new RpcHeader();
    header.setMagicNumber(frame.readInt());          // 4 字节
    header.setVersion(frame.readByte());             // 1 字节
    header.setSerializerType(frame.readByte());      // 1 字节
    header.setMessageType(frame.readByte());         // 1 字节
    header.setReserved(frame.readByte());            // 1 字节
    header.setRequestId(frame.readLong());           // 8 字节
    header.setBodyLength(frame.readInt());           // 4 字节
    
    // 3. 校验魔数
    if (header.getMagicNumber() != RpcHeader.MAGIC_NUMBER) {
        log.error("魔数校验失败：{}", header.getMagicNumber());
        frame.release();
        throw new IllegalArgumentException("非法的 RPC 消息，魔数不匹配");
    }
    
    // ⭐ 新增：校验协议版本
    if (header.getVersion() != RpcHeader.VERSION) {
        log.error("不支持的协议版本：{}，当前支持版本：{}", 
                 header.getVersion(), RpcHeader.VERSION);
        frame.release();
        // 抛出异常，Netty 会关闭连接
        throw new UnsupportedOperationException(
            "不支持的协议版本：" + header.getVersion() + 
            "，当前支持版本：" + RpcHeader.VERSION
        );
    }
    
    // 4. 读取消息体（后续代码保持不变）
    byte[] bodyBytes = new byte[header.getBodyLength()];
    frame.readBytes(bodyBytes);
    
    // ... 后续代码省略 ...
}
```

**说明**：
- 在解码器的 `decode()` 方法中添加版本检查逻辑
- 如果版本号不匹配，释放 ByteBuf 资源并抛出异常
- Netty 框架捕获异常后会关闭连接，防止处理错误数据

---

### 练习 2：实现心跳消息

**题目**：设计心跳消息的格式
- 心跳请求：只包含请求 ID
- 心跳响应：包含请求 ID 和时间戳

**答案**：

#### （1）定义心跳消息结构

```java
package com.rpc.core.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 心跳消息（用于保持连接活跃）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcHeartbeat {
    
    /** 请求 ID（用于匹配请求和响应） */
    private long requestId;
    
    /** 时间戳（仅响应时需要） */
    private long timestamp;
    
    /**
     * 创建心跳请求
     */
    public static RpcHeartbeat createRequest(long requestId) {
        return RpcHeartbeat.builder()
                .requestId(requestId)
                .build();
    }
    
    /**
     * 创建心跳响应
     */
    public static RpcHeartbeat createResponse(long requestId) {
        return RpcHeartbeat.builder()
                .requestId(requestId)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
```

#### （2）扩展消息类型枚举

```java
// 已有的 MessageType 接口中添加新类型
public interface MessageType {
    byte REQUEST = 1;
    byte RESPONSE = 2;
    
    // ⭐ 新增心跳消息类型
    byte HEARTBEAT_REQUEST = 3;
    byte HEARTBEAT_RESPONSE = 4;
}
```

#### （3）修改编码器支持心跳消息

```java
@Override
protected void encode(ChannelHandlerContext ctx, RpcMessage msg, 
                     ByteBuf out) throws Exception {
    
    RpcHeader header = msg.getHeader();
    Object body = msg.getBody();
    
    // ⭐ 根据消息类型选择不同的序列化方式
    byte[] bodyBytes;
    if (header.getMessageType() == MessageType.HEARTBEAT_REQUEST ||
        header.getMessageType() == MessageType.HEARTBEAT_RESPONSE) {
        // 心跳消息使用简单的 JSON 序列化
        bodyBytes = JsonUtils.toJsonBytes(body);
    } else {
        // 普通 RPC 消息使用配置的序列化器
        Serializer serializer = SerializerFactory.getSerializer(
            header.getSerializerType()
        );
        bodyBytes = serializer.serialize(body);
    }
    
    header.setBodyLength(bodyBytes.length);
    
    // 写入消息头（20 字节）
    out.writeInt(header.getMagicNumber());
    out.writeByte(header.getVersion());
    out.writeByte(header.getSerializerType());
    out.writeByte(header.getMessageType());
    out.writeByte(header.getReserved());
    out.writeLong(header.getRequestId());
    out.writeInt(header.getBodyLength());
    
    // 写入消息体
    out.writeBytes(bodyBytes);
}
```

#### （4）修改解码器支持心跳消息

```java
@Override
protected Object decode(ChannelHandlerContext ctx, ByteBuf in) 
        throws Exception {
    
    // ... 前面的代码不变 ...
    
    // ⭐ 根据消息类型选择反序列化方式
    Object body;
    if (header.getMessageType() == MessageType.HEARTBEAT_REQUEST ||
        header.getMessageType() == MessageType.HEARTBEAT_RESPONSE) {
        // 心跳消息反序列化为 RpcHeartbeat
        body = JsonUtils.fromJson(bodyBytes, RpcHeartbeat.class);
    } else if (header.getMessageType() == MessageType.REQUEST) {
        body = serializer.deserialize(bodyBytes, RpcRequest.class);
    } else {  // RESPONSE
        body = serializer.deserialize(bodyBytes, RpcResponse.class);
    }
    
    // ... 后续代码不变 ...
}
```

#### （5）心跳消息格式设计

```
心跳请求消息：
┌──────────────────────────────────────┐
│ Header (20 字节)                      │
│ Magic(4) + Version(1) + ...          │
│ MessageType=3 (1 字节) ← 心跳请求     │
│ RequestID(8) + BodyLength(4)         │
├──────────────────────────────────────┤
│ Body (变长，JSON 格式)                │
│ {"requestId": 123456}                │
└──────────────────────────────────────┘

心跳响应消息：
┌──────────────────────────────────────┐
│ Header (20 字节)                      │
│ Magic(4) + Version(1) + ...          │
│ MessageType=4 (1 字节) ← 心跳响应     │
│ RequestID(8) + BodyLength(4)         │
├──────────────────────────────────────┤
│ Body (变长，JSON 格式)                │
│ {"requestId": 123456,                │
│  "timestamp": 1703123456789}         │
└──────────────────────────────────────┘
```

**说明**：
- 心跳消息结构简单，使用 JSON 序列化即可（不需要复杂的 Kryo）
- 通过 `MessageType` 区分心跳消息和普通 RPC 消息
- 心跳响应包含时间戳，可用于计算网络延迟

---

### 练习 3：添加校验和

**题目**：在消息头中添加校验和字段（CRC32），接收方校验数据完整性。

**答案**：

#### （1）修改 RpcHeader 添加校验和字段

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcHeader {
    
    /** 魔数，4 字节 */
    private int magicNumber = 0x12345678;
    
    /** 版本号，1 字节 */
    private byte version = 1;
    
    /** 序列化器类型，1 字节 */
    private byte serializerType;
    
    /** 消息类型，1 字节 */
    private byte messageType;
    
    /** 保留字段，1 字节 */
    private byte reserved;
    
    /** 请求 ID，8 字节 */
    private long requestId;
    
    /** 消息体长度，4 字节 */
    private int bodyLength;
    
    // ⭐ 新增：校验和，4 字节（放在消息头末尾）
    private long checksum;
    
    /** 消息头总长度：24 字节（原 20 字节 + 新增 4 字节） */
    public static final int HEADER_LENGTH = 24;
    
    /** 魔数常量 */
    public static final int MAGIC_NUMBER = 0x12345678;
    
    /** 协议版本 */
    public static final byte VERSION = 1;
}
```

#### （2）修改协议格式图

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          Magic Number                         |
+---------------------------------------------------------------+
|   Version     |   Serializer  |    MessageType   |  Reserved  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Request ID                            |
|                       (8 bytes)                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Body Length                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          Checksum                             |
|                       (4 bytes)                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**新的消息头长度**：4 + 4 + 4 + 8 + 4 + 4 = **28 字节**

#### （3）修改编码器计算校验和

```java
@Override
protected void encode(ChannelHandlerContext ctx, RpcMessage msg, 
                     ByteBuf out) throws Exception {
    
    RpcHeader header = msg.getHeader();
    Object body = msg.getBody();
    
    // 1. 获取序列化器并序列化消息体
    Serializer serializer = SerializerFactory.getSerializer(
        header.getSerializerType()
    );
    byte[] bodyBytes = serializer.serialize(body);
    
    // 2. 更新消息头
    header.setBodyLength(bodyBytes.length);
    
    // ⭐ 3. 计算 CRC32 校验和
    CRC32 crc32 = new CRC32();
    crc32.update(bodyBytes);  // 对消息体计算校验和
    header.setChecksum(crc32.getValue());
    
    // 4. 写入消息头（28 字节）
    out.writeInt(header.getMagicNumber());           // 4 字节
    out.writeByte(header.getVersion());              // 1 字节
    out.writeByte(header.getSerializerType());       // 1 字节
    out.writeByte(header.getMessageType());          // 1 字节
    out.writeByte(header.getReserved());             // 1 字节
    out.writeLong(header.getRequestId());            // 8 字节
    out.writeInt(header.getBodyLength());            // 4 字节
    out.writeInt((int) header.getChecksum());        // 4 字节 ⭐
    
    // 5. 写入消息体
    out.writeBytes(bodyBytes);
    
    log.debug("编码完成：requestId={}, bodyLength={}, checksum={}", 
             header.getRequestId(), header.getBodyLength(), header.getChecksum());
}
```

#### （4）修改解码器校验校验和

```java
@Override
protected Object decode(ChannelHandlerContext ctx, ByteBuf in) 
        throws Exception {
    
    // 1. 调用父类方法，获取完整的帧
    ByteBuf frame = (ByteBuf) super.decode(ctx, in);
    if (frame == null) {
        return null;
    }
    
    // 2. 读取消息头
    RpcHeader header = new RpcHeader();
    header.setMagicNumber(frame.readInt());          // 4 字节
    header.setVersion(frame.readByte());             // 1 字节
    header.setSerializerType(frame.readByte());      // 1 字节
    header.setMessageType(frame.readByte());         // 1 字节
    header.setReserved(frame.readByte());            // 1 字节
    header.setRequestId(frame.readLong());           // 8 字节
    header.setBodyLength(frame.readInt());           // 4 字节
    header.setChecksum(frame.readUnsignedInt());     // 4 字节 ⭐
    
    // 3. 校验魔数
    if (header.getMagicNumber() != RpcHeader.MAGIC_NUMBER) {
        log.error("魔数校验失败：{}", header.getMagicNumber());
        frame.release();
        throw new IllegalArgumentException("非法的 RPC 消息，魔数不匹配");
    }
    
    // ⭐ 4. 校验数据完整性（CRC32）
    byte[] bodyBytes = new byte[header.getBodyLength()];
    frame.readBytes(bodyBytes);
    
    CRC32 crc32 = new CRC32();
    crc32.update(bodyBytes);
    long calculatedChecksum = crc32.getValue();
    
    if (calculatedChecksum != header.getChecksum()) {
        log.error("CRC32 校验失败！期望：{}, 实际：{}", 
                 header.getChecksum(), calculatedChecksum);
        frame.release();
        throw new IOException("数据完整性校验失败，可能已损坏");
    }
    
    // 5. 反序列化消息体
    Serializer serializer = SerializerFactory.getSerializer(
        header.getSerializerType()
    );
    Object body = serializer.deserialize(bodyBytes, Object.class);
    
    frame.release();
    
    // 6. 构建 RpcMessage
    RpcMessage message = new RpcMessage();
    message.setHeader(header);
    message.setBody(body);
    
    log.debug("解码完成：requestId={}, bodyLength={}, checksum 校验通过", 
             header.getRequestId(), header.getBodyLength());
    
    return message;
}
```

#### （5）LengthFieldBasedFrameDecoder 参数调整

```java
public RpcProtocolDecoder() {
    super(
        1024 * 1024,    // 最大 1MB
        20,             // ⭐ 长度字段偏移量改为 20（原 16）
        4,              // 长度字段长度
        0,              // 长度调整值
        0               // 跳过的字节数
    );
}
```

**说明**：
- 由于消息头从 20 字节扩展到 28 字节，长度字段的偏移量需要调整
- `lengthFieldOffset = 20`（从第 21 字节开始是长度字段）

#### （6）CRC32 校验原理

```
发送方：
1. 对 bodyBytes 计算 CRC32 值
2. 将 CRC32 值写入 header.checksum
3. 发送：header + bodyBytes

接收方：
1. 接收数据，读取 header.checksum
2. 对收到的 bodyBytes 重新计算 CRC32
3. 比较：计算的 CRC32 vs header 中的 CRC32
4. 如果不相等 → 数据在传输过程中损坏 → 抛出异常
```

**优点**：
- ✅ 检测数据传输过程中的损坏
- ✅ 检测网络传输错误
- ✅ 提高可靠性

**缺点**：
- ❌ 增加 4 字节开销
- ❌ 增加 CPU 计算成本
- ❌ 对于高吞吐场景可能影响性能

---

## 十一、课后思考参考答案

### 1. 为什么要设计魔数？魔数有什么作用？

**答案要点**：

**魔数（Magic Number）**是一个特殊的数值标识，用于快速识别数据包的有效性。

**作用**：
1. **快速识别协议**：通过魔数可以立即判断这个数据包是否是我们预期的协议格式
2. **过滤非法数据**：如果魔数不匹配，说明收到了错误的数据包，可以快速丢弃
3. **安全防护**：防止恶意攻击或错误连接发送的垃圾数据
4. **调试便利**：在日志中看到魔数就能知道是哪个系统的数据

**生活中的例子**：
```
就像信封上的邮政编码：
- 看到 100000 开头的邮编，就知道这是北京的信件
- 如果收到一个没有邮编或邮编错误的信封，可能是垃圾邮件
```

**常见魔数示例**：
- Java class 文件：`0xCAFEBABE`
- PNG 图片：`0x89504E47`
- gzip 文件：`0x1F8B0800`

---

### 2. 消息头为什么要用固定长度？

**答案要点**：

**原因**：
1. **解析简单高效**：固定长度意味着可以直接定位到每个字段的位置
   ```java
   // 固定长度：直接读取
   offset = 0;
   magic = buffer.readInt(offset);      // 0-3
   version = buffer.readByte(offset+4); // 4
   
   // 变长：需要先解析前面的字段才能知道下一个字段位置
   // 复杂且容易出错
   ```

2. **性能好**：不需要额外的长度信息，减少一次内存读取

3. **实现简单**：编解码器逻辑清晰，不易出错

4. **内存对齐**：CPU 访问固定长度的数据结构效率更高

**缺点**：
- 不够灵活，扩展性稍差
- 但可以通过预留字段（reserved）来解决

---

### 3. 除了长度字段，还有哪些解决粘包的方法？

**答案**：

#### 方法 1：固定长度
- 每条消息固定长度（如都是 1024 字节）
- 不足部分用 0 填充
- **适用场景**：消息长度固定的场景

#### 方法 2：特殊分隔符
- 使用特殊字符（如 `\r\n`）分隔消息
- Netty 提供：`LineBasedFrameDecoder`
- **适用场景**：文本协议（如 HTTP、FTP）

#### 方法 3：自定义协议头
- 在协议头中包含长度字段（我们采用的方式）
- Netty 提供：`LengthFieldBasedFrameDecoder`
- **适用场景**：二进制协议，最常用

#### 方法 4：基于内容的边界
- 根据消息内容的特征来判断边界
- 例如：XML/JSON 的 `{}` 标签匹配
- **适用场景**：结构化数据

#### 方法 5：应用层协议
- 使用现成的应用层协议（如 HTTP/2、gRPC）
- 这些协议已经定义好了消息边界
- **适用场景**：通用性要求高的场景

---

### 4. 如何在不中断服务的情况下升级协议版本？

**答案要点**：

#### 策略 1：多版本兼容

```java
// 解码器支持多个版本
if (header.getVersion() == 1) {
    // 按 V1 版本解析
    parseV1(header, bodyBytes);
} else if (header.getVersion() == 2) {
    // 按 V2 版本解析
    parseV2(header, bodyBytes);
}
```

#### 策略 2：灰度升级

1. **第一阶段**：新旧版本并存
   - 服务端同时支持 V1 和 V2 协议
   - 根据请求中的 version 字段决定使用哪个版本

2. **第二阶段**：逐步切换
   - 先升级少量客户端到 V2
   - 监控运行稳定后，继续扩大范围

3. **第三阶段**：淘汰旧版本
   - 所有客户端都升级到 V2 后
   - 移除 V1 版本的支持代码

#### 策略 3：协商机制

```
客户端连接时：
1. 客户端发送支持的版本列表：[1, 2]
2. 服务端返回选择的版本：2
3. 后续通信使用版本 2
```

#### 策略 4：向后兼容设计

- 新增字段放在消息末尾
- 使用可选字段（optional）
- 不删除已有字段，只标记 deprecated

**最佳实践**：
- ✅ 协议设计时就考虑版本兼容性
- ✅ 使用灰度发布，避免一次性全部升级
- ✅ 保留回滚能力，出现问题可快速恢复

---

### 练习 1：添加协议版本检查

修改解码器，当收到不支持的协议版本时，抛出异常并关闭连接。

### 练习 2：实现心跳消息

设计心跳消息的格式：
- 心跳请求：只包含请求 ID
- 心跳响应：包含请求 ID 和时间戳

### 练习 3：添加校验和

在消息头中添加校验和字段（CRC32），接收方校验数据完整性。

提示：
```java
// 计算 CRC32
CRC32 crc32 = new CRC32();
crc32.update(bodyBytes);
long checksum = crc32.getValue();
```

---

## 十一、下一步

下一节课我们将实现**Netty 服务端**，启动 RPC 服务器。

**[跳转到第 6 课：Netty 服务端实现](./lesson-06-netty-server.md)**
