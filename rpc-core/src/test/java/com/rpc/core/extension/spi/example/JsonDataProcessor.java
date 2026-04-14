package com.rpc.core.extension.spi.example;

import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.spi.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 始终使用 JSON（文本序列化格式）序列化器的示例处理器。
 */
public class JsonDataProcessor implements DataProcessor {
    private static final Logger log = LoggerFactory.getLogger(JsonDataProcessor.class);

    @Inject("json")
    private Serializer jsonSerializer;

    @Override
    public String process(String data) {
        log.info("Process data with JSON serializer");
        byte[] bytes = jsonSerializer.serialize(data);
        String result = jsonSerializer.deserialize(bytes, String.class);
        return "Processed by " + getName() + ": " + result;
    }

    @Override
    public String getName() {
        return "JsonDataProcessor";
    }

    public Serializer getJsonSerializer() {
        return jsonSerializer;
    }
}
