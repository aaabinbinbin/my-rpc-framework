package com.rpc.core.extension.spi.example;

public class FlakyProcessorImpl implements FlakyProcessor {
    @Override
    public String getName() {
        return "FlakyProcessorImpl";
    }
}
