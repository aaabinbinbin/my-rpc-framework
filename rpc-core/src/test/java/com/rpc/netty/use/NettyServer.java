package com.rpc.netty.use;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

public class NettyServer {
    public static void main(String[] args) throws InterruptedException {
        // 1. 创建 Boss Group 和 Worker Group
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            // 2. 创建服务器启动引导
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            // 3. 配置处理器链
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new StringDecoder()); // 解码器
                            pipeline.addLast(new StringEncoder()); // 编码器
                            pipeline.addLast(new ServerHandler()); // 业务处理器
                        }
                    });
            // 4. 绑定端口并启动
            ChannelFuture future = bootstrap.bind(8080).sync();
            System.out.println("Netty 服务器启动成功，监听端口：8080");
            // 5. 等待服务关闭
            future.channel().closeFuture().sync();
        } finally {
            // 6. 优雅关闭
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    /**
     * 服务端处理器
     */
    static class ServerHandler extends SimpleChannelInboundHandler<String> {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            System.out.println("客户端连接：" + ctx.channel().remoteAddress());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            System.out.println("收到客户端消息：" + msg);

            // 回复消息
            ctx.writeAndFlush("服务端已收到：" + msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            System.out.println("客户端断开连接：" + ctx.channel().remoteAddress());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}
