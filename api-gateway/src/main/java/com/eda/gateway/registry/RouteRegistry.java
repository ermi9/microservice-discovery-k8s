package com.eda.gateway.registry;

import com.eda.gateway.model.RouteInfo;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RouteRegistry {

    private final ConcurrentHashMap<String, RouteInfo> routes = new ConcurrentHashMap<>();

    public void add(RouteInfo info) {
        routes.put(info.getServiceName(), info);
    }

    public void remove(String serviceName) {
        routes.remove(serviceName);
    }

    public RouteInfo get(String serviceName) {
        return routes.get(serviceName);
    }

    public Collection<RouteInfo> getAll() {
        return Collections.unmodifiableCollection(routes.values());
    }
}
