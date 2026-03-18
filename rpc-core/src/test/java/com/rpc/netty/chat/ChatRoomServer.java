package com.rpc.netty.chat;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ChatRoomServer {
    public static void main(String[] args) throws InterruptedException {
        // 1. 创建 Boss Group 和 Worker Group
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) throws Exception {
                            ChannelPipeline pipeline = channel.pipeline();
                            pipeline.addLast(new StringDecoder());
                            pipeline.addLast(new StringEncoder());
                            pipeline.addLast(new ServerHandler());
                        }
                    });
            ChannelFuture future = bootstrap.bind(8080).sync();
            System.out.println("聊天室服务器启动成功，监听端口：8080");
            future.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    /**当一个客户端连接时，整个流程是这样的：
     * 1. 服务器启动
     *    pipeline.addLast(new ChatRoomServerHandler())
     *             ↓
     *    handlerAdded() 被调用
     *
     * 2. 客户端连接
     *    channelActive() 被调用
     *
     * 3. 客户端发送消息
     *    channelRead0() 被调用
     *
     * 4. 客户端断开
     *    channelInactive() 被调用
     *
     * 5. 关闭 Channel 或移除 Handler
     *    handlerRemoved() 被调用
     */
    /**
     * 聊天室服务端处理器
     */
    @ChannelHandler.Sharable // 标记该Handler可以被多个Channel共享
    private static class ServerHandler extends SimpleChannelInboundHandler<String> { // 泛型是消息类型
        private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        private List<Channel> channels = new ArrayList<>();

        // 这个打印主要是为了调试和观察，让你知道 Handler 已经被添加到 Pipeline 了
        // pipeline.addLast(new ChatRoomServerHandler());这个时候触发
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            System.out.println("[系统] 有客户端准备连接...");
        }

        // 客户端刚连接上时触发（可以发欢迎语、做初始化）
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            // 客户端连接时，广播通知所有人
            String message = "[系统] " + getTimestamp() + " - 用户 " +
                    ctx.channel().remoteAddress() + " 加入聊天室！\n";

            // 将当前客户端添加到组
            channels.add(ctx.channel());

            // 广播给所有客户端（包括刚连接的）
            broadcast(message);

            System.out.println("[系统] 当前在线人数：" + channels.size());
        }

        // 接收到消息时触发，
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
            // 收到客户端消息，广播给所有人
            String message = "[" + getTimestamp() + "] " +
                    ctx.channel().remoteAddress() + " 说：" + msg + "\n";

            broadcast(message);
        }

        // 连接断开时触发（清理资源、统计信息等）
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // 客户端断开时，广播通知所有人
            String message = "[系统] " + getTimestamp() + " - 用户 " +
                    ctx.channel().remoteAddress() + " 离开聊天室！\n";

            // 从组中移除
            channels.remove(ctx.channel());

            // 广播给剩余的客户端
            broadcast(message);

            System.out.println("[系统] 当前在线人数：" + channels.size());
        }

        // 发生异常时触发（通常打印日志并关闭连接）
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }

        /**
         * 广播消息给所有连接的客户端
         */
        private void broadcast(String message) {
            for (Channel channel : channels) {
                channel.writeAndFlush(message);
            }
        }

        private String getTimestamp() {
            return sdf.format(new Date());
        }
    }
}
