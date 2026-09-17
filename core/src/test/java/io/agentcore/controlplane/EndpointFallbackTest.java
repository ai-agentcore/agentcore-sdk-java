package io.agentcore.controlplane;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;

class EndpointFallbackTest {
    @Test void onlyConnectionFailuresOfImplicitReadEndpointsMayFallback() {
        var publicEndpoint = URI.create("https://agentcore.cn-hangzhou.aliyuncs.com");
        assertEquals("agentcore-vpc.cn-hangzhou.aliyuncs.com",
            EndpointFallback.controlPlane(publicEndpoint, "cn-hangzhou", false, "ListModels", new ConnectException()).getHost());
        assertNull(EndpointFallback.controlPlane(publicEndpoint, "cn-hangzhou", true, "ListModels", new ConnectException()));
        assertNull(EndpointFallback.controlPlane(publicEndpoint, "cn-hangzhou", false, "AddMemory", new ConnectException()));
        assertNull(EndpointFallback.controlPlane(publicEndpoint, "cn-hangzhou", false, "ListModels", new IllegalArgumentException()));
    }
    @Test void signedOssQueryIsUnchangedAndSignedHostDisablesFallback() {
        String url = "https://bucket.oss-cn-hangzhou.aliyuncs.com/archive.zip?Signature=a%2Bb&x=1";
        assertEquals(url.replace("oss-cn-hangzhou.", "oss-cn-hangzhou-internal."), EndpointFallback.oss(URI.create(url)).toString());
        assertNull(EndpointFallback.oss(URI.create(url + "&x-oss-additional-headers=content-type%3Bhost")));
        assertNull(EndpointFallback.oss(URI.create("https://custom.example.com/archive.zip")));
        assertNull(EndpointFallback.oss(URI.create(url.replace("oss-cn-hangzhou", "oss-cn-hangzhou-internal"))));
    }
}
