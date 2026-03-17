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

## 六、实现编解码器

### 6.1 RPC 协议编码器

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

### 6.2 RPC 协议解码器

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

## 七、测试编解码器

### 7.1 单元测试

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

## 八、本课总结

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
   - 编码器：`MessageToByteEncoder`
   - 解码器：`LengthFieldBasedFrameDecoder`

### 课后思考

1. 为什么要设计魔数？魔数有什么作用？
2. 消息头为什么要用固定长度？
3. 除了长度字段，还有哪些解决粘包的方法？
4. 如何在不中断服务的情况下升级协议版本？

---

## 九、动手练习

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

## 十、下一步

下一节课我们将实现**Netty 服务端**，启动 RPC 服务器。

**[跳转到第 6 课：Netty 服务端实现](./lesson-06-netty-server.md)**
