package com.rpc.core.extension.spi.example;

import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.spi.Initialize;
import com.rpc.core.extension.spi.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用于演示默认注入和具名注入的示例处理器。
 */
public class DefaultDataProcessor implements DataProcessor {
    private static final Logger log = LoggerFactory.getLogger(DefaultDataProcessor.class);

    @Inject
    private Serializer serializer;

    @Inject("json")
    private Serializer jsonSerializer;

    @Inject(value = "nonexistent", required = false)
    private Serializer optionalSerializer;

    private boolean initialized = false;

    @Override
    public String process(String data) {
        log.info("Process data with serializer {}", serializer.getClass().getSimpleName());
        byte[] bytes = serializer.serialize(data);
        String result = serializer.deserialize(bytes, String.class);
        return "Processed by " + getName() + ": " + result;
    }

    @Override
    public String getName() {
        return "DefaultDataProcessor";
    }

    @Initialize
    public void init() {
        log.info("DefaultDataProcessor initialized, serializer={}",
                serializer != null ? serializer.getClass().getSimpleName() : "null");
        this.initialized = true;
    }

    public Serializer getSerializer() {
        return serializer;
    }

    public Serializer getJsonSerializer() {
        return jsonSerializer;
    }

    public Serializer getOptionalSerializer() {
        return optionalSerializer;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
