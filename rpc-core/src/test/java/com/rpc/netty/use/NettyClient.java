package com.rpc.netty.use;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

public class NettyClient {
    public static void main(String[] args) throws InterruptedException {
        // 1. 创建事件循环组（客户端只需要一个）
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            // 2. 创建客户端启动引导
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new StringDecoder());
                            pipeline.addLast(new StringEncoder());
                            pipeline.addLast(new ClientHandler());
                        }
                    });
            // 3. 连接服务器
            ChannelFuture future = bootstrap.connect("127.0.0.1", 8080).sync();
            System.out.println("Netty 客户端启动成功");
            // 4. 发送消息
            Channel channel = future.channel();
            channel.writeAndFlush("你好，服务端！");
            // 等待 1 秒接收响应
            Thread.sleep(1000);
            // 5. 关闭连接
            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    /**
     * 客户端处理器
     */
    static class ClientHandler extends SimpleChannelInboundHandler<String> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            System.out.println("收到服务端响应：" + msg);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            System.out.println("已连接到服务端");
        }
    }
}
