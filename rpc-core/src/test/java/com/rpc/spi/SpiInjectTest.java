package com.rpc.core.extension.spi;

import com.rpc.core.extension.serialize.impl.JsonSerializer;
import com.rpc.core.extension.serialize.impl.KryoSerializer;
import com.rpc.core.extension.spi.example.DataProcessor;
import com.rpc.core.extension.spi.example.OptionalInjectProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpiInjectTest {

    @Test
    void shouldSupportNamedAndDefaultInjection() {
        OptionalInjectProcessor processor = (OptionalInjectProcessor)
                ExtensionFactory.getExtension(DataProcessor.class, "optional");

        assertNotNull(processor.getRequiredSerializer());
        assertInstanceOf(KryoSerializer.class, processor.getRequiredSerializer());
        assertNotNull(processor.getDefaultSerializer());
        assertInstanceOf(KryoSerializer.class, processor.getDefaultSerializer());
    }

    @Test
    void shouldSkipOptionalMissingDependency() {
        OptionalInjectProcessor processor = (OptionalInjectProcessor)
                ExtensionFactory.getExtension(DataProcessor.class, "optional");

        assertNull(processor.getOptionalNonexistentSerializer());
    }

    @Test
    void shouldInjectOptionalExistingDependency() {
        OptionalInjectProcessor processor = (OptionalInjectProcessor)
                ExtensionFactory.getExtension(DataProcessor.class, "optional");

        assertNotNull(processor.getOptionalExistingSerializer());
        assertInstanceOf(JsonSerializer.class, processor.getOptionalExistingSerializer());
    }

    @Test
    void injectedProcessorShouldRemainUsable() {
        DataProcessor processor = ExtensionFactory.getExtension(DataProcessor.class, "optional");

        assertEquals("Processed by OptionalInjectProcessor", processor.process("payload"));
    }
}
