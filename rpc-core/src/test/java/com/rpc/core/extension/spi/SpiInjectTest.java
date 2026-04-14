package com.rpc.core.extension.spi;

import com.rpc.core.extension.serialize.impl.JsonSerializer;
import com.rpc.core.extension.serialize.impl.KryoSerializer;
import com.rpc.core.extension.serialize.impl.ProtobufSerializer;
import com.rpc.core.extension.spi.example.DataProcessor;
import com.rpc.core.extension.spi.example.OptionalInjectProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：SPI注入测试")
class SpiInjectTest {

    @DisplayName("验证支持命名并默认Injection场景")
    @Test
    void shouldSupportNamedAndDefaultInjection() {
        OptionalInjectProcessor processor = (OptionalInjectProcessor)
                ExtensionFactory.getExtension(DataProcessor.class, "optional");

        assertNotNull(processor.getRequiredSerializer());
        assertInstanceOf(KryoSerializer.class, processor.getRequiredSerializer());
        assertNotNull(processor.getDefaultSerializer());
        assertInstanceOf(ProtobufSerializer.class, processor.getDefaultSerializer());
    }

    @DisplayName("验证跳过可选缺失依赖场景")
    @Test
    void shouldSkipOptionalMissingDependency() {
        OptionalInjectProcessor processor = (OptionalInjectProcessor)
                ExtensionFactory.getExtension(DataProcessor.class, "optional");

        assertNull(processor.getOptionalNonexistentSerializer());
    }

    @DisplayName("验证注入可选存在的依赖场景")
    @Test
    void shouldInjectOptionalExistingDependency() {
        OptionalInjectProcessor processor = (OptionalInjectProcessor)
                ExtensionFactory.getExtension(DataProcessor.class, "optional");

        assertNotNull(processor.getOptionalExistingSerializer());
        assertInstanceOf(JsonSerializer.class, processor.getOptionalExistingSerializer());
    }

    @DisplayName("验证injected处理器Should保持可用场景")
    @Test
    void injectedProcessorShouldRemainUsable() {
        DataProcessor processor = ExtensionFactory.getExtension(DataProcessor.class, "optional");

        assertEquals("Processed by OptionalInjectProcessor", processor.process("payload"));
    }
}
