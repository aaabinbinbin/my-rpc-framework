package com.rpc.core.extension.spi.example;

import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.spi.Initialize;
import com.rpc.core.extension.spi.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 用于演示默认注入和具名注入的示例处理器。
 */
@Slf4j
@Getter
public class DefaultDataProcessor implements DataProcessor {
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
}
