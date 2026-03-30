package com.rpc.core.invoke.filter;

@FunctionalInterface
public interface FilterChain {
    Object proceed(FilterContext context) throws Exception;
}

