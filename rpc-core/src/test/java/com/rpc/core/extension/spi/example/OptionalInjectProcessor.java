package com.rpc.core.extension.spi.example;

import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.spi.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用于验证可选注入行为的示例处理器。
 */
public class OptionalInjectProcessor implements DataProcessor {
    private static final Logger log = LoggerFactory.getLogger(OptionalInjectProcessor.class);

    @Inject("kryo")
    private Serializer requiredSerializer;

    @Inject(value = "nonexistent_serializer", required = false)
    private Serializer optionalNonexistentSerializer;

    @Inject(value = "json", required = false)
    private Serializer optionalExistingSerializer;

    @Inject
    private Serializer defaultSerializer;

    @Override
    public String process(String data) {
        log.info("Optional injection test: required={}, optionalMissing={}, optionalExisting={}, default={}",
                requiredSerializer != null ? requiredSerializer.getClass().getSimpleName() : "null",
                optionalNonexistentSerializer != null ? optionalNonexistentSerializer.getClass().getSimpleName() : "null",
                optionalExistingSerializer != null ? optionalExistingSerializer.getClass().getSimpleName() : "null",
                defaultSerializer != null ? defaultSerializer.getClass().getSimpleName() : "null");
        return "Processed by OptionalInjectProcessor";
    }

    @Override
    public String getName() {
        return "OptionalInjectProcessor";
    }

    public Serializer getRequiredSerializer() {
        return requiredSerializer;
    }

    public Serializer getOptionalNonexistentSerializer() {
        return optionalNonexistentSerializer;
    }

    public Serializer getOptionalExistingSerializer() {
        return optionalExistingSerializer;
    }

    public Serializer getDefaultSerializer() {
        return defaultSerializer;
    }
}
