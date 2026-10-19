package io.agentcore.controlplane;

import io.agentcore.AgentCoreException;
import io.agentcore.HttpTransport;
import java.net.URI;
import reactor.core.publisher.Mono;

/** Presigned resource downloads share the existing public-to-private OSS fallback. */
public final class ArchiveDownload {
    private ArchiveDownload() {}
    public static Mono<byte[]> get(HttpTransport http, URI url, int maxBytes) {
        return http.download(url, maxBytes).onErrorResume(error -> {
            URI fallback = EndpointFallback.oss(url);
            boolean unavailable = EndpointFallback.connectionFailure(error)
                || error instanceof AgentCoreException failure && failure.status() != null && failure.status() >= 500;
            if (fallback == null || !unavailable) return Mono.error(error);
            org.slf4j.LoggerFactory.getLogger(ArchiveDownload.class).warn("agentcore.archive.download.fallback host={}", fallback.getHost());
            return http.download(fallback, maxBytes);
        });
    }
}
