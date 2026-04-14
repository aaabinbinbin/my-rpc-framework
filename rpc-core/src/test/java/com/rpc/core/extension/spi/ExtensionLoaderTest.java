package com.rpc.core.extension.spi;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.spi.example.AdvancedDataProcessor;
import com.rpc.core.extension.spi.example.AliasProcessor;
import com.rpc.core.extension.spi.example.DataProcessor;
import com.rpc.core.extension.spi.example.FlakyProcessor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：扩展加载器测试")
class ExtensionLoaderTest {

    @DisplayName("验证负载支持的扩展并默认扩展场景")
    @Test
    void shouldLoadSupportedExtensionsAndDefaultExtension() {
        ExtensionLoader<DataProcessor> loader = ExtensionLoader.getExtensionLoader(DataProcessor.class);

        Set<String> names = loader.getSupportedExtensions();
        assertTrue(names.contains("default"));
        assertTrue(names.contains("json"));
        assertTrue(names.contains("advanced"));
    }

    @DisplayName("验证返回单例扩展实例场景")
    @Test
    void shouldReturnSingletonExtensionInstances() {
        ExtensionLoader<DataProcessor> loader = ExtensionLoader.getExtensionLoader(DataProcessor.class);

        DataProcessor first = loader.getExtension("default");
        DataProcessor second = loader.getExtension("default");

        assertSame(first, second);
    }

    @DisplayName("验证解析默认扩展来自SPI注解场景")
    @Test
    void shouldResolveDefaultExtensionFromSpiAnnotation() {
        DataProcessor processor = ExtensionFactory.getDefaultExtension(DataProcessor.class);

        assertNotNull(processor);
        assertEquals("DefaultDataProcessor", processor.getName());
    }

    @DisplayName("验证注入依赖并调用初始化方法场景")
    @Test
    void shouldInjectDependenciesAndInvokeInitializeMethod() {
        AdvancedDataProcessor processor = (AdvancedDataProcessor)
                ExtensionFactory.getExtension(DataProcessor.class, "advanced");

        assertNotNull(processor.getSerializer());
        assertTrue(processor.getSerializer() instanceof Serializer);
        assertNotNull(processor.getLoadBalancer());
        assertTrue(processor.getLoadBalancer() instanceof LoadBalancer);
        assertTrue(processor.isInitialized());
        assertEquals("initialized-config", processor.getConfig());
    }

    @DisplayName("验证拒绝非SPI类型场景")
    @Test
    void shouldRejectNonSpiTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> ExtensionLoader.getExtensionLoader(String.class));
    }

    @DisplayName("验证允许并发别名解析用于同时实现类场景")
    @Test
    void shouldAllowConcurrentAliasResolutionForSameImplementationClass() throws Exception {
        ExtensionFactory.clearCache();
        ExtensionLoader<AliasProcessor> loader = ExtensionLoader.getExtensionLoader(AliasProcessor.class);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();
        AtomicReference<AliasProcessor> first = new AtomicReference<>();
        AtomicReference<AliasProcessor> second = new AtomicReference<>();

        Thread threadA = new Thread(() -> resolveAlias(loader, "aliasA", start, first, firstError));
        Thread threadB = new Thread(() -> resolveAlias(loader, "aliasB", start, second, secondError));
        threadA.start();
        threadB.start();
        start.countDown();
        threadA.join();
        threadB.join();

        assertNull(firstError.get());
        assertNull(secondError.get());
        assertNotNull(first.get());
        assertNotNull(second.get());
        assertEquals("AliasProcessorImpl", first.get().getName());
        assertEquals("AliasProcessorImpl", second.get().getName());
    }

    @DisplayName("验证重试资源加载在上一次Initialization失败场景")
    @Test
    void shouldRetryResourceLoadingAfterPreviousInitializationFailure() {
        ExtensionFactory.clearCache();
        String resourceName = "META-INF/rpc/" + FlakyProcessor.class.getName();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader failing = new ClassLoader(original) {
            @Override
            public java.util.Enumeration<URL> getResources(String name) throws IOException {
                if (resourceName.equals(name)) {
                    throw new IOException("boom");
                }
                return super.getResources(name);
            }
        };

        try {
            Thread.currentThread().setContextClassLoader(failing);
            ExtensionLoader<FlakyProcessor> loader = ExtensionLoader.getExtensionLoader(FlakyProcessor.class);
            assertTrue(loader.getSupportedExtensions().isEmpty());

            Thread.currentThread().setContextClassLoader(original);
            assertTrue(loader.getSupportedExtensions().contains("stable"));
            assertEquals("FlakyProcessorImpl", loader.getExtension("stable").getName());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            ExtensionFactory.clearCache();
        }
    }

    private static void resolveAlias(ExtensionLoader<AliasProcessor> loader,
                                     String alias,
                                     CountDownLatch start,
                                     AtomicReference<AliasProcessor> result,
                                     AtomicReference<Throwable> error) {
        try {
            start.await();
            result.set(loader.getExtension(alias));
        } catch (Throwable throwable) {
            error.set(throwable);
        }
    }
}
