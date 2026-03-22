package com.rpc.protocol.codec;

import com.rpc.codec.RpcProtocolDecoder;
import com.rpc.codec.RpcProtocolEncoder;
import com.rpc.protocol.RpcHeader;
import com.rpc.protocol.RpcHeartbeat;
import com.rpc.protocol.RpcMessage;
import com.rpc.protocol.RpcMessageType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * RPC 心跳消息编解码测试
 */
@Slf4j
public class RpcHeartbeatCodecTest {

    /**
     * 测试心跳请求的编码和解码
     */
    @Test
    public void testHeartbeatRequestEncodeDecode() {
        log.info("========== 开始测试心跳请求编解码 ==========");

        // 1. 创建心跳请求
        long requestId = new Random().nextLong();
        RpcHeartbeat heartbeat = RpcHeartbeat.createRequest(requestId);
        log.info("创建心跳请求：requestId={}, timestamp={}", 
                heartbeat.getRequestId(), heartbeat.getTimestamp());

        // 2. 创建消息头
        RpcHeader header = RpcHeader.builder()
                .magicNumber(0x12345678)
                .version((byte) 1)
                .messageType(RpcMessageType.HEARTBEAT_REQUEST.getCode())  // 心跳请求类型
                .serializerType((byte) 1)  // Kryo 序列化
                .requestId(requestId)
                .build();

        // 3. 创建完整的 RPC 消息
        RpcMessage message = new RpcMessage();
        message.setHeader(header);
        message.setBody(heartbeat);
        log.info("编码前的消息：{}", message);

        // 4. 创建编码通道
        EmbeddedChannel encoderChannel = new EmbeddedChannel(new RpcProtocolEncoder());

        // 5. 编码（出站操作）
        log.info("开始编码...");
        assertTrue("编码应该成功", encoderChannel.writeOutbound(message));
        ByteBuf encoded = (ByteBuf) encoderChannel.readOutbound();
        assertNotNull("编码后的 ByteBuf 不应为空", encoded);
        log.info("编码完成，字节数：{} (header=20 + body={})", 
                encoded.readableBytes(), header.getBodyLength());

        // 6. 创建解码通道
        EmbeddedChannel decoderChannel = new EmbeddedChannel(new RpcProtocolDecoder());

        // 7. 解码（入站操作，使用 retainedDuplicate 防止释放）
        log.info("开始解码...");
        assertTrue("解码应该成功", decoderChannel.writeInbound(encoded.retainedDuplicate()));
        RpcMessage decoded = (RpcMessage) decoderChannel.readInbound();
        assertNotNull("解码后的消息不应为空", decoded);

        // 8. 验证解码结果
        log.info("========== 验证解码结果 ==========");
        
        // 验证消息头
        assertEquals("魔数应该匹配", 0x12345678, decoded.getHeader().getMagicNumber());
        assertEquals("版本号应该匹配", 1, decoded.getHeader().getVersion());
        assertEquals("消息类型应该是心跳请求", RpcMessageType.HEARTBEAT_REQUEST,
                    decoded.getHeader().getMessageType());
        assertEquals("请求 ID 应该匹配", requestId, decoded.getHeader().getRequestId());

        // 验证消息体
        Object body = decoded.getBody();
        assertNotNull("消息体不应为空", body);
        assertTrue("消息体应该是 RpcHeartbeat 类型", body instanceof RpcHeartbeat);
        
        RpcHeartbeat decodedHeartbeat = (RpcHeartbeat) body;
        assertEquals("请求 ID 应该匹配", requestId, decodedHeartbeat.getRequestId());
        assertEquals("心跳请求的时间戳应为 0", 0, decodedHeartbeat.getTimestamp());
        
        log.info("解码后的心跳请求：requestId={}, timestamp={}", 
                decodedHeartbeat.getRequestId(), decodedHeartbeat.getTimestamp());
        log.info("========== 心跳请求编解码测试通过 ==========");

        // 9. 关闭通道
        encoderChannel.finish();
        decoderChannel.finish();
    }

    /**
     * 测试心跳响应的编码和解码
     */
    @Test
    public void testHeartbeatResponseEncodeDecode() {
        log.info("========== 开始测试心跳响应编解码 ==========");

        // 1. 创建心跳响应（带时间戳）
        long requestId = new Random().nextLong();
        RpcHeartbeat heartbeat = RpcHeartbeat.createResponse(requestId);
        log.info("创建心跳响应：requestId={}, timestamp={}", 
                heartbeat.getRequestId(), heartbeat.getTimestamp());

        // 2. 创建消息头
        RpcHeader header = RpcHeader.builder()
                .magicNumber(0x12345678)
                .version((byte) 1)
                .messageType(RpcMessageType.HEARTBEAT_RESPONSE.getCode())  // 心跳响应类型
                .serializerType((byte) 1)  // Kryo 序列化
                .requestId(requestId)
                .build();

        // 3. 创建完整的 RPC 消息
        RpcMessage message = new RpcMessage();
        message.setHeader(header);
        message.setBody(heartbeat);
        log.info("编码前的消息：{}", message);

        // 4. 创建编码通道
        EmbeddedChannel encoderChannel = new EmbeddedChannel(new RpcProtocolEncoder());

        // 5. 编码（出站操作）
        log.info("开始编码...");
        assertTrue("编码应该成功", encoderChannel.writeOutbound(message));
        ByteBuf encoded = (ByteBuf) encoderChannel.readOutbound();
        assertNotNull("编码后的 ByteBuf 不应为空", encoded);
        log.info("编码完成，字节数：{} (header=20 + body={})", 
                encoded.readableBytes(), header.getBodyLength());

        // 6. 创建解码通道
        EmbeddedChannel decoderChannel = new EmbeddedChannel(new RpcProtocolDecoder());

        // 7. 解码（入站操作，使用 retainedDuplicate 防止释放）
        log.info("开始解码...");
        assertTrue("解码应该成功", decoderChannel.writeInbound(encoded.retainedDuplicate()));
        RpcMessage decoded = (RpcMessage) decoderChannel.readInbound();
        assertNotNull("解码后的消息不应为空", decoded);

        // 8. 验证解码结果
        log.info("========== 验证解码结果 ==========");
        
        // 验证消息头
        assertEquals("魔数应该匹配", 0x12345678, decoded.getHeader().getMagicNumber());
        assertEquals("版本号应该匹配", 1, decoded.getHeader().getVersion());
        assertEquals("消息类型应该是心跳响应", RpcMessageType.HEARTBEAT_RESPONSE,
                    decoded.getHeader().getMessageType());
        assertEquals("请求 ID 应该匹配", requestId, decoded.getHeader().getRequestId());

        // 验证消息体
        Object body = decoded.getBody();
        assertNotNull("消息体不应为空", body);
        assertTrue("消息体应该是 RpcHeartbeat 类型", body instanceof RpcHeartbeat);
        
        RpcHeartbeat decodedHeartbeat = (RpcHeartbeat) body;
        assertEquals("请求 ID 应该匹配", requestId, decodedHeartbeat.getRequestId());
        assertTrue("心跳响应应该包含时间戳", decodedHeartbeat.getTimestamp() > 0);
        
        log.info("解码后的心跳响应：requestId={}, timestamp={}", 
                decodedHeartbeat.getRequestId(), decodedHeartbeat.getTimestamp());
        log.info("========== 心跳响应编解码测试通过 ==========");

        // 9. 关闭通道
        encoderChannel.finish();
        decoderChannel.finish();
    }

    /**
     * 测试完整的心跳请求 - 响应流程
     */
    @Test
    public void testHeartbeatRequestResponseFlow() {
        log.info("========== 开始测试完整的心跳请求 - 响应流程 ==========");

        long requestId = 123456L;
        EmbeddedChannel channel = new EmbeddedChannel(new RpcProtocolDecoder(), new RpcProtocolEncoder());

        // ========== 客户端发送心跳请求 ==========
        log.info("【步骤 1】客户端发送心跳请求");
        
        RpcHeartbeat requestHeartbeat = RpcHeartbeat.createRequest(requestId);
        RpcHeader requestHeader = RpcHeader.builder()
                .magicNumber(0x12345678)
                .version((byte) 1)
                .messageType(RpcMessageType.HEARTBEAT_REQUEST.getCode())
                .serializerType((byte) 1)
                .requestId(requestId)
                .build();
        
        RpcMessage requestMessage = new RpcMessage();
        requestMessage.setHeader(requestHeader);
        requestMessage.setBody(requestHeartbeat);
        
        // 写出一帧数据
        channel.writeOutbound(requestMessage);
        ByteBuf outboundData = (ByteBuf) channel.readOutbound();
        log.info("客户端发送心跳请求完成，字节数：{}", outboundData.readableBytes());

        // ========== 服务端接收并处理心跳请求 ==========
        log.info("【步骤 2】服务端接收心跳请求");
        
        // 模拟网络传输：将 outbound 数据写入 inbound
        channel.writeInbound(outboundData.retainedDuplicate());
        RpcMessage receivedRequest = (RpcMessage) channel.readInbound();
        
        assertNotNull("服务端应该收到心跳请求", receivedRequest);
        assertEquals("消息类型应该是心跳请求", RpcMessageType.HEARTBEAT_REQUEST,
                    receivedRequest.getHeader().getMessageType());
        
        RpcHeartbeat receivedHeartbeat = (RpcHeartbeat) receivedRequest.getBody();
        assertEquals("请求 ID 应该匹配", requestId, receivedHeartbeat.getRequestId());
        log.info("服务端收到心跳请求：requestId={}", receivedHeartbeat.getRequestId());

        // ========== 服务端返回心跳响应 ==========
        log.info("【步骤 3】服务端返回心跳响应");
        
        RpcHeartbeat responseHeartbeat = RpcHeartbeat.createResponse(requestId);
        RpcHeader responseHeader = RpcHeader.builder()
                .magicNumber(0x12345678)
                .version((byte) 1)
                .messageType(RpcMessageType.HEARTBEAT_RESPONSE.getCode())
                .serializerType((byte) 1)
                .requestId(requestId)
                .build();
        
        RpcMessage responseMessage = new RpcMessage();
        responseMessage.setHeader(responseHeader);
        responseMessage.setBody(responseHeartbeat);
        
        channel.writeOutbound(responseMessage);
        ByteBuf responseData = (ByteBuf) channel.readOutbound();
        log.info("服务端发送心跳响应完成，字节数：{}", responseData.readableBytes());

        // ========== 客户端接收心跳响应 ==========
        log.info("【步骤 4】客户端接收心跳响应");
        
        channel.writeInbound(responseData.retainedDuplicate());
        RpcMessage receivedResponse = (RpcMessage) channel.readInbound();
        
        assertNotNull("客户端应该收到心跳响应", receivedResponse);
        assertEquals("消息类型应该是心跳响应", RpcMessageType.HEARTBEAT_RESPONSE,
                    receivedResponse.getHeader().getMessageType());
        
        RpcHeartbeat receivedResponseHeartbeat = (RpcHeartbeat) receivedResponse.getBody();
        assertEquals("请求 ID 应该匹配", requestId, receivedResponseHeartbeat.getRequestId());
        assertTrue("响应应该包含时间戳", receivedResponseHeartbeat.getTimestamp() > 0);
        
        log.info("客户端收到心跳响应：requestId={}, timestamp={}", 
                receivedResponseHeartbeat.getRequestId(), receivedResponseHeartbeat.getTimestamp());
        
        // 计算网络延迟（可选）
        long networkDelay = System.currentTimeMillis() - receivedResponseHeartbeat.getTimestamp();
        log.info("估算的网络延迟：{} ms", networkDelay);
        
        log.info("========== 完整的心跳请求 - 响应流程测试通过 ==========");
        
        // 关闭通道
        channel.finish();
    }

    /**
     * 测试多个心跳消息的连续编解码
     */
    @Test
    public void testMultipleHeartbeats() {
        log.info("========== 开始测试多个心跳消息的连续编解码 ==========");

        EmbeddedChannel encoderChannel = new EmbeddedChannel(new RpcProtocolEncoder());
        EmbeddedChannel decoderChannel = new EmbeddedChannel(new RpcProtocolDecoder());

        // 发送 5 个心跳请求
        for (int i = 0; i < 5; i++) {
            long requestId = 1000L + i;
            
            // 创建心跳请求
            RpcHeartbeat heartbeat = RpcHeartbeat.createRequest(requestId);
            RpcHeader header = RpcHeader.builder()
                    .magicNumber(0x12345678)
                    .version((byte) 1)
                    .messageType(RpcMessageType.HEARTBEAT_REQUEST.getCode())
                    .serializerType((byte) 1)
                    .requestId(requestId)
                    .build();
            
            RpcMessage message = new RpcMessage();
            message.setHeader(header);
            message.setBody(heartbeat);
            
            // 编码
            encoderChannel.writeOutbound(message);
            ByteBuf encoded = (ByteBuf) encoderChannel.readOutbound();
            
            // 解码
            decoderChannel.writeInbound(encoded.retainedDuplicate());
            RpcMessage decoded = (RpcMessage) decoderChannel.readInbound();
            
            // 验证
            assertNotNull("第 " + (i + 1) + " 个心跳消息解码不应为空", decoded);
            assertEquals("第 " + (i + 1) + " 个心跳消息的请求 ID 应该匹配", 
                        requestId, decoded.getHeader().getRequestId());
            
            RpcHeartbeat decodedHeartbeat = (RpcHeartbeat) decoded.getBody();
            assertEquals("第 " + (i + 1) + " 个心跳消息的请求 ID 应该匹配", 
                        requestId, decodedHeartbeat.getRequestId());
            
            log.info("心跳消息 #{}: requestId={}, 编解码成功", i + 1, requestId);
        }

        log.info("========== 多个心跳消息连续编解码测试通过 ==========");
        
        // 关闭通道
        encoderChannel.finish();
        decoderChannel.finish();
    }
}
