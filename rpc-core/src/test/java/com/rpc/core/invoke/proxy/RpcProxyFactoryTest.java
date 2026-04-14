package com.rpc.core.invoke.proxy;

import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.transport.RpcTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("测试类：RPC 代理工厂和代理调用边界测试")
class RpcProxyFactoryTest {
    @DisplayName("验证接口代理会走 JDK 动态代理并转换为 RpcRequest")
    @Test
    void shouldCreateJdkProxyAndTranslateInvocationToRpcRequest() {
        CapturingTransport transport = new CapturingTransport(RpcResponse.success("hello rpc", "req-1"));
        EchoService proxy = RpcProxyFactory.create(transport).createProxyInstance(EchoService.class);

        String result = proxy.echo("rpc");

        assertTrue(Proxy.isProxyClass(proxy.getClass()));
        assertEquals("hello rpc", result);
        assertEquals(EchoService.class.getName(), transport.lastRequest.get().getServiceName());
        assertEquals("echo", transport.lastRequest.get().getMethodName());
        assertEquals(String.class, transport.lastRequest.get().getReturnType());
        assertEquals("rpc", transport.lastRequest.get().getParameters()[0]);
    }

    @DisplayName("验证 Object 基础方法不会触发远程调用")
    @Test
    void shouldHandleObjectMethodsLocally() {
        CapturingTransport transport = new CapturingTransport(RpcResponse.success("unused", "req-1"));
        EchoService proxy = RpcProxyFactory.create(transport).createProxyInstance(EchoService.class);

        String text = proxy.toString();
        int hash = proxy.hashCode();
        boolean equalsSelf = proxy.equals(proxy);

        assertTrue(text.contains(EchoService.class.getName()));
        assertEquals(System.identityHashCode(proxy), hash);
        assertTrue(equalsSelf);
        assertFalse(proxy.equals(new Object()));
        assertEquals(0, transport.sendCount);
    }

    @DisplayName("验证远程非成功响应会转换为调用异常")
    @Test
    void shouldThrowWhenRemoteResponseIsNotSuccess() {
        CapturingTransport transport = new CapturingTransport(RpcResponse.fail(500, "remote error", "req-1"));
        EchoService proxy = RpcProxyFactory.create(transport).createProxyInstance(EchoService.class);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> proxy.echo("rpc"));

        assertTrue(exception.getMessage().contains("remote error"));
        assertEquals(1, transport.sendCount);
    }

    @DisplayName("验证未初始化传输客户端时代理创建路径会快速失败")
    @Test
    void shouldFailFastWhenClientMissing() {
        RpcProxyFactory factory = RpcProxyFactory.create((RpcTransport) null);

        assertThrows(IllegalStateException.class, () -> factory.createProxyInstance(EchoService.class));
        assertThrows(IllegalStateException.class, () -> RpcProxyFactory.createProxy(EchoService.class));
    }

    @DisplayName("验证普通类代理会走 CGLIB 并转换为 RpcRequest")
    @Test
    void shouldCreateCglibProxyForConcreteClass() {
        CapturingTransport transport = new CapturingTransport(RpcResponse.success("hi rpc", "req-1"));
        EchoClass proxy = RpcProxyFactory.create(transport).createProxyInstance(EchoClass.class);

        String result = proxy.echo("rpc");

        assertInstanceOf(EchoClass.class, proxy);
        assertEquals("hi rpc", result);
        assertEquals(EchoClass.class.getName(), transport.lastRequest.get().getServiceName());
        assertEquals("echo", transport.lastRequest.get().getMethodName());
    }

    interface EchoService {
        String echo(String name);
    }

    public static class EchoClass {
        public String echo(String name) {
            return "local " + name;
        }
    }

    private static final class CapturingTransport implements RpcTransport {
        private final RpcResponse response;
        private final AtomicReference<RpcRequest> lastRequest = new AtomicReference<>();
        private int sendCount;

        private CapturingTransport(RpcResponse response) {
            this.response = response;
        }

        @Override
        public RpcResponse sendRequest(RpcRequest rpcRequest) {
            sendCount++;
            lastRequest.set(rpcRequest);
            return response;
        }

        @Override
        public void close() {
        }
    }
}
