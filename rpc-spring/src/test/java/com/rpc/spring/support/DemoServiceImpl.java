package com.rpc.spring.support;

import com.rpc.core.api.annotation.RpcService;

@RpcService(DemoService.class)
public class DemoServiceImpl implements DemoService {
    @Override
    public String hello() {
        return "hello";
    }
}

