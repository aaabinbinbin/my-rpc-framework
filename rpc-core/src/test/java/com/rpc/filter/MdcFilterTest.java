package com.rpc.core.invoke.filter;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.impl.MdcFilter;
import com.rpc.core.protocol.RpcRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MdcFilterTest {
    @Test
    void shouldPopulateAndClearMdc() throws Exception {
        MdcFilter filter = new MdcFilter();
        RpcContext rpcContext = RpcContext.create()
                .setRequestId("req-1")
                .setTraceId("trace-1");
        FilterContext context = FilterContext.builder()
                .rpcContext(rpcContext)
                .request(RpcRequest.builder()
                        .serviceName("svc")
                        .methodName("m")
                        .build())
                .build();

        filter.invoke(context, next -> {
            assertEquals("req-1", MDC.get("rpcRequestId"));
            assertEquals("trace-1", MDC.get("rpcTraceId"));
            assertEquals("svc", MDC.get("rpcService"));
            assertEquals("m", MDC.get("rpcMethod"));
            return null;
        });

        assertNull(MDC.get("rpcRequestId"));
        assertNull(MDC.get("rpcTraceId"));
        RpcContext.clear();
    }
}

