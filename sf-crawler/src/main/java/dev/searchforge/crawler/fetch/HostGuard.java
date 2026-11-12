package dev.searchforge.crawler.fetch;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

// SSRF guard: a public page can link to http://192.168.1.1/ or http://localhost:8080/.
// Unless private hosts are explicitly allowed (local testing), refuse any host that
// resolves to a loopback, private, link-local, CGNAT, multicast or unspecified address.
public final class HostGuard {

    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowPrivate;
    private final Resolver resolver;

    public HostGuard(boolean allowPrivate) {
        this(allowPrivate, InetAddress::getAllByName);
    }

    public HostGuard(boolean allowPrivate, Resolver resolver) {
        this.allowPrivate = allowPrivate;
        this.resolver = resolver;
    }

    public boolean isAllowed(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (allowPrivate) {
            return true;
        }
        try {
            InetAddress[] addrs = resolver.resolve(host);
            if (addrs.length == 0) {
                return false;
            }
            for (InetAddress a : addrs) {
                if (isNonPublic(a)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static boolean isNonPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19)) || first >= 240;
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc; // fc00::/7 unique local
        }
        return true;
    }
}
