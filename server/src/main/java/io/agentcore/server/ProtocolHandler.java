package io.agentcore.server;

import reactor.netty.http.server.HttpServerRoutes;

/** Owns protocol routes and encoding. Invoke the supplied Agent entry point from those routes. */
@FunctionalInterface
public interface ProtocolHandler { void register(HttpServerRoutes routes, InvokeHandler agent); }
