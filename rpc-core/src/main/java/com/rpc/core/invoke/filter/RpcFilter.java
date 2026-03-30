package com.rpc.core.invoke.filter;

import com.rpc.core.extension.spi.SPI;

@SPI
public interface RpcFilter {
    FilterPhase phase();

    int order();

    Object invoke(FilterContext context, FilterChain chain) throws Exception;
}

