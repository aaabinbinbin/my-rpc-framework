package com.rpc.core.extension.spi.example;

import com.rpc.core.extension.spi.SPI;

@SPI("aliasA")
public interface AliasProcessor {
    String getName();
}
