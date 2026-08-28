package com.atest.service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.atest.config.AtestProperties;
import org.springframework.stereotype.Component;

/**
 * SSRF guard for open-API callback URLs. The callback is a server-side outbound POST to a
 * caller-supplied URL, so an unchecked URL lets any API caller make the server probe its own
 * loopback services, the internal network or a cloud metadata endpoint (169.254.169.254).
 *
 * <p>Policy ({@code atest.callback.allowed-hosts}, entries are hostnames, IPs or CIDR blocks):
 * <ul>
 *   <li><b>allowlist empty</b> (default): generic public http(s) targets stay allowed, but any
 *       URL whose host resolves to loopback / link-local (incl. the metadata IP) / RFC1918 /
 *       CGNAT / IPv6 unique-local / unspecified / multicast is rejected;</li>
 *   <li><b>allowlist non-empty</b>: only listed hosts are allowed — a hostname entry matches the
 *       URL host literally, an IP/CIDR entry matches every resolved address. Listing a private
 *       host/range is the explicit opt-in for internal callback receivers.</li>
 * </ul>
 *
 * <p>The same check runs at ingest (create task → 400) and again right before every delivery
 * attempt, so a DNS record that flips to a private IP after create (rebinding) is still caught.
 * There remains a small window between this check and the HTTP client's own lookup; combined
 * with redirects being disabled this is an accepted residual risk for the gray release.
 */
@Component
public class CallbackUrlPolicy {

    private final AtestProperties props;

    public CallbackUrlPolicy(AtestProperties props) {
        this.props = props;
    }

    /** @return {@code null} when the URL may be called, otherwise a user-facing reject reason. */
    public String rejectReason(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return "callbackUrl 不是合法 URL";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "callbackUrl 缺少主机名";
        }
        host = stripBrackets(host).toLowerCase(Locale.ROOT);
        List<Entry> entries = parseEntries();
        if (!entries.isEmpty()) {
            return allowedByList(host, entries) ? null
                    : "callbackUrl 主机 " + host + " 不在 atest.callback.allowed-hosts 白名单内";
        }
        InetAddress[] resolved = resolveOrNull(host);
        if (resolved == null) {
            // unresolvable right now: let the attempt fail naturally on connect; the same check
            // re-runs before every delivery, so a later malicious resolution is still caught
            return null;
        }
        for (InetAddress addr : resolved) {
            if (isForbidden(addr)) {
                return "callbackUrl 主机 " + host + " 解析到受保护地址 " + addr.getHostAddress()
                        + "（loopback/内网/link-local 等默认禁止），如确属可信回调接收方，"
                        + "请把它加进 atest.callback.allowed-hosts";
            }
        }
        return null;
    }

    // ------------------------------------------------------------- allowlist

    private boolean allowedByList(String host, List<Entry> entries) {
        for (Entry entry : entries) {
            if (entry.hostname != null && entry.hostname.equals(host)) {
                return true;
            }
        }
        List<Entry> cidrs = entries.stream().filter(e -> e.hostname == null).toList();
        if (cidrs.isEmpty()) {
            return false;
        }
        InetAddress[] resolved = resolveOrNull(host);
        if (resolved == null || resolved.length == 0) {
            // allowlist mode is strict: an unresolvable host cannot prove it is in range
            return false;
        }
        // every resolved address must be covered, or a multi-record answer could smuggle in
        // one in-range address next to the attacker's real target
        for (InetAddress addr : resolved) {
            boolean covered = false;
            for (Entry cidr : cidrs) {
                if (cidr.contains(addr)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    private List<Entry> parseEntries() {
        List<String> raw = props.getCallback().getAllowedHosts();
        List<Entry> entries = new ArrayList<>();
        if (raw == null) {
            return entries;
        }
        for (String item : raw) {
            String v = item == null ? "" : item.trim();
            if (v.isEmpty()) {
                continue;
            }
            Entry entry = Entry.parse(v);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /** One allowlist entry: either a literal hostname, or an IP/CIDR range. */
    private static final class Entry {
        final String hostname;      // lowercase, null for IP/CIDR entries
        final byte[] network;       // null for hostname entries
        final int prefixBits;

        private Entry(String hostname, byte[] network, int prefixBits) {
            this.hostname = hostname;
            this.network = network;
            this.prefixBits = prefixBits;
        }

        static Entry parse(String value) {
            String host = value;
            int prefix = -1;
            int slash = value.indexOf('/');
            if (slash >= 0) {
                host = value.substring(0, slash).trim();
                try {
                    prefix = Integer.parseInt(value.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            host = stripBrackets(host);
            byte[] addr = literalIpOrNull(host);
            if (addr == null) {
                // not an IP literal: a CIDR suffix makes no sense on a hostname
                return slash >= 0 ? null : new Entry(host.toLowerCase(Locale.ROOT), null, 0);
            }
            int max = addr.length * 8;
            if (prefix < 0) {
                prefix = max;
            }
            if (prefix > max) {
                return null;
            }
            return new Entry(null, addr, prefix);
        }

        boolean contains(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            int remainder = prefixBits % 8;
            if (remainder == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainder);
            return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    // ------------------------------------------------------------- built-ins

    /** Addresses that are never a legitimate open-API callback target unless allowlisted. */
    static boolean isForbidden(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (addr instanceof Inet4Address && b.length == 4) {
            // 100.64.0.0/10 (CGNAT) is inside-the-fence in most clouds
            return (b[0] & 0xff) == 100 && (b[1] & 0xff) >= 64 && (b[1] & 0xff) <= 127;
        }
        if (b.length == 16) {
            // fc00::/7 unique-local: the IPv6 counterpart of RFC1918
            return (b[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    private static InetAddress[] resolveOrNull(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Parses an IPv4/IPv6 literal without triggering DNS; returns null for hostnames. */
    private static byte[] literalIpOrNull(String host) {
        boolean ipLike = host.indexOf(':') >= 0 || host.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'));
        if (!ipLike) {
            return null;
        }
        try {
            return InetAddress.getByName(host).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String stripBrackets(String host) {
        if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
