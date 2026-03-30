package com.rpc.core.invoke.filter;

@FunctionalInterface
public interface FilterInvoker {
    Object invoke(FilterContext context) throws Exception;
}

