package io.agentcore.controlplane;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Endpoint selection only; authenticated requests and presigned queries are never redirected. */
public final class EndpointFallback {
    private EndpointFallback() {}
    public static URI controlPlane(URI current, String region, boolean explicit, String action, Throwable error) {
        if (explicit || !current.getHost().equals("agentcore." + region + ".aliyuncs.com")
            || !(action.startsWith("List") || action.startsWith("Get") || action.startsWith("Search") || action.startsWith("Download"))
            || !connectionFailure(error)) return null;
        return URI.create("https://agentcore-vpc." + region + ".aliyuncs.com");
    }
    public static boolean connectionFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException
                || cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException)
                return true;
        return false;
    }
    public static URI oss(URI url) {
        if (url.getUserInfo() != null || url.getHost() == null) return null;
        var match = Pattern.compile("([a-z0-9-]+)\\.(oss-[a-z]+-[a-z0-9-]+)\\.aliyuncs\\.com").matcher(url.getHost());
        if (!match.matches() || match.group(2).endsWith("-internal") || match.group(2).startsWith("oss-accelerate")) return null;
        for (String part : (url.getRawQuery() == null ? "" : url.getRawQuery()).split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equalsIgnoreCase("x-oss-additional-headers")
                && Arrays.stream(URLDecoder.decode(pair[1], StandardCharsets.UTF_8).split(";")).anyMatch("host"::equalsIgnoreCase)) return null;
        }
        return URI.create(url.getScheme() + "://" + match.group(1) + "." + match.group(2) + "-internal.aliyuncs.com"
            + (url.getPort() < 0 ? "" : ":" + url.getPort()) + url.getRawPath()
            + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery()));
    }
}
