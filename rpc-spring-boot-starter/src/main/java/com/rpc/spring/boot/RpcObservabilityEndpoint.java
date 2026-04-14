package com.rpc.spring.boot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RPC 可观测 HTTP 端点。
 *
 * 所处阶段：Spring Boot Web 应用启动后注册为普通 Controller。
 * 主要职责：把 rpc-core 中的运行时指标快照暴露为只读 HTTP 接口，便于压测、排障和面试演示。
 *
 * 注意事项：该端点仅在 Servlet Web 环境且 rpc.observability.http.enabled=true 时启用。
 */
@RestController
@RequestMapping("/rpc/observability")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "rpc.observability.http", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RpcObservabilityEndpoint {
    /** 可观测门面，负责从 core 层抓取并裁剪指标快照。 */
    private final RpcObservabilityFacade observabilityFacade;

    /**
     * 创建可观测 HTTP 端点。
     *
     * @param observabilityFacade 可观测门面
     */
    public RpcObservabilityEndpoint(RpcObservabilityFacade observabilityFacade) {
        this.observabilityFacade = observabilityFacade;
    }

    /**
     * 查询 RPC 运行时指标快照。
     *
     * 边界处理：服务指标可能很多，默认不展开；展开时也会通过 limit 做上限保护，避免接口返回过大。
     *
     * @param includeServices 是否返回服务维度指标明细
     * @param limit 服务明细最大返回数量，为空或非法时使用默认上限
     * @return 可用于 HTTP JSON 序列化的可观测响应对象
     */
    @GetMapping
    public RpcObservabilityResponse snapshot(
            @RequestParam(name = "includeServices", defaultValue = "false") boolean includeServices,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        return observabilityFacade.httpSnapshot(includeServices, limit);
    }
}
