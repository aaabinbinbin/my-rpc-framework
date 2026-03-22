package com.rpc.spi.example;

import com.rpc.spi.SPI;

/**
 * 数据处理器接口
 * 
 * 用于演示 SPI 依赖注入功能
 */
@SPI("default")
public interface DataProcessor {
    
    /**
     * 处理数据
     */
    String process(String data);
    
    /**
     * 获取处理器名称
     */
    String getName();
}