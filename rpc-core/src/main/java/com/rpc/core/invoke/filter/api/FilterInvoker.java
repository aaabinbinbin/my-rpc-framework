package com.rpc.core.invoke.filter.api;

import com.rpc.core.invoke.filter.context.FilterContext;

/**
 * 过滤器链末端调用器。
 *
 * 所处阶段：某一阶段过滤器全部执行完成后。
 * 主要职责：封装最终动作，例如 consumer 阶段进入集群调用、invoker 阶段进入 transport attempt、provider 阶段进入业务方法。
 */
@FunctionalInterface
public interface FilterInvoker {
    /**
     * 执行过滤器链末端逻辑。
     */
    Object invoke(FilterContext context) throws Exception;
}

