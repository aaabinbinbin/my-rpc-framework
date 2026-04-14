package com.rpc.core.extension.spi.example;

import com.rpc.core.extension.spi.SPI;

@SPI("stable")
public interface FlakyProcessor {
    String getName();
}
