package com.rpc.netty.chat;

import com.rpc.netty.use.NettyClient;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ChatRoomClient {
    public static void main(String[] args) throws InterruptedException {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) throws Exception {
                            ChannelPipeline pipeline = channel.pipeline();
                            pipeline.addLast(new StringDecoder());
                            pipeline.addLast(new StringEncoder());
                            pipeline.addLast(new ChatRoomClientHandler());
                        }
                    });
            ChannelFuture future = bootstrap.connect("127.0.0.1", 8080).sync();
            System.out.println("已连接到聊天室服务器");
            // 启动一个线程读取用户输入
            Channel channel = future.channel();
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

            new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("exit".equals(line)) {
                            channel.closeFuture().sync();
                            break;
                        }
                        channel.writeAndFlush(line);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
            // 等待连接关闭
            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    /**
     * 客户端处理器
     */
    static class ChatRoomClientHandler extends SimpleChannelInboundHandler<String> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            // 收到服务端广播的消息，直接打印
            System.out.print(msg);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            System.out.println("=== 欢迎进入聊天室 ===");
            System.out.println("输入消息后按回车发送，输入 exit 退出");
        }
    }
}
