package com.rpc.core.extension.spi.example;

import com.rpc.core.extension.spi.SPI;

/**
 * 单元测试使用的示例 SPI（可插拔扩展点）。
 */
@SPI("default")
public interface DataProcessor {
    String process(String data);

    String getName();
}
