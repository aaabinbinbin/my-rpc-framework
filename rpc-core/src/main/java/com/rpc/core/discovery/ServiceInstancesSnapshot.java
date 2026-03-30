package com.rpc.core.discovery;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
@EqualsAndHashCode
public final class ServiceInstancesSnapshot {
    private final String serviceName;
    private final List<InetSocketAddress> addresses;

    private ServiceInstancesSnapshot(String serviceName, List<InetSocketAddress> addresses) {
        this.serviceName = serviceName;
        this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
    }

    public static ServiceInstancesSnapshot of(String serviceName, List<InetSocketAddress> addresses) {
        return new ServiceInstancesSnapshot(serviceName, addresses == null ? List.of() : addresses);
    }

    public boolean isEmpty() {
        return addresses.isEmpty();
    }
}

