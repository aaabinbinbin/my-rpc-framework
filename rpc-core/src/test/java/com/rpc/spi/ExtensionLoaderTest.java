package com.rpc.core.extension.spi;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.spi.example.AdvancedDataProcessor;
import com.rpc.core.extension.spi.example.DataProcessor;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionLoaderTest {

    @Test
    void shouldLoadSupportedExtensionsAndDefaultExtension() {
        ExtensionLoader<DataProcessor> loader = ExtensionLoader.getExtensionLoader(DataProcessor.class);

        Set<String> names = loader.getSupportedExtensions();
        assertTrue(names.contains("default"));
        assertTrue(names.contains("json"));
        assertTrue(names.contains("advanced"));
    }

    @Test
    void shouldReturnSingletonExtensionInstances() {
        ExtensionLoader<DataProcessor> loader = ExtensionLoader.getExtensionLoader(DataProcessor.class);

        DataProcessor first = loader.getExtension("default");
        DataProcessor second = loader.getExtension("default");

        assertSame(first, second);
    }

    @Test
    void shouldResolveDefaultExtensionFromSpiAnnotation() {
        DataProcessor processor = ExtensionFactory.getDefaultExtension(DataProcessor.class);

        assertNotNull(processor);
        assertEquals("DefaultDataProcessor", processor.getName());
    }

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

    @Test
    void shouldRejectNonSpiTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> ExtensionLoader.getExtensionLoader(String.class));
    }
}
