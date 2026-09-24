package com.example.urlshortener.admission;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The unit a rate limit is counted against: one IPv4 address, or one IPv6 {@code /64}.
 *
 * <p>IPv6 is truncated because a single subscriber is routinely delegated a whole {@code /64} and
 * can rotate through it freely; keying on the full address would hand every such client an
 * effectively unlimited supply of fresh buckets, and grow the bucket map without bound.
 *
 * @param value the IPv4 address, or the IPv6 prefix in the form {@code 2001:db8:0:1::/64}
 */
public record RateLimitKey(String value) {

    private static final int IPV6_PREFIX_GROUPS = 4;

    /** Derives the key for {@code caller}, whose address is an IP literal from the servlet container. */
    public static RateLimitKey from(ClientIdentity caller) {
        String address = caller.remoteAddress();
        if (address.indexOf(':') < 0) {
            return new RateLimitKey(address);
        }
        return new RateLimitKey(ipv6Key(address));
    }

    private static String ipv6Key(String address) {
        // A zone ID ("fe80::1%eth0") names a local interface; resolving it can fail on this host.
        int zoneStart = address.indexOf('%');
        String literal = zoneStart < 0 ? address : address.substring(0, zoneStart);
        InetAddress parsed;
        try {
            // Contains ':' so it cannot be a host name: this parses, it never performs a DNS lookup.
            parsed = InetAddress.getByName(literal);
        } catch (UnknownHostException malformed) {
            // Container-supplied, so not attacker-rotatable; counting it as-is stays bounded.
            return address;
        }
        if (parsed instanceof Inet4Address mapped) {
            // "::ffff:192.0.2.1" is the IPv4 client 192.0.2.1 and must share its bucket.
            return mapped.getHostAddress();
        }
        byte[] bytes = parsed.getAddress();
        StringBuilder prefix = new StringBuilder();
        for (int group = 0; group < IPV6_PREFIX_GROUPS; group++) {
            int hextet = ((bytes[2 * group] & 0xff) << 8) | (bytes[2 * group + 1] & 0xff);
            prefix.append(Integer.toHexString(hextet)).append(':');
        }
        return prefix.append(":/64").toString();
    }
}
