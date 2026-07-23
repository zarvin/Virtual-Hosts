package com.github.xfalcon.vhosts.vservice;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class DnsChangeTest {

    // 测试与被测类同包（com.github.xfalcon.vhosts.vservice），
    // 可直接读取 package-private 的 DOMAINS_IP_MAPS4/6，无需在生产代码里加测试专用方法。

    @Test
    public void singleProfileParsing() {
        List<String> profiles = new ArrayList<>();
        profiles.add("127.0.0.1 a.com\n127.0.0.1 b.com\n");
        DnsChange.loadProfiles(profiles);
        assertEquals("127.0.0.1", DnsChange.DOMAINS_IP_MAPS4.get("a.com."));
        assertEquals("127.0.0.1", DnsChange.DOMAINS_IP_MAPS4.get("b.com."));
    }

    @Test
    public void multipleProfilesMergeFrontPriority() {
        // 靠前的方案优先：同域名，靠后方案不覆盖靠前方案（putIfAbsent 语义）
        List<String> profiles = Arrays.asList(
            "127.0.0.1 example.com\n",
            "192.168.1.1 example.com\n127.0.0.1 other.com\n"
        );
        DnsChange.loadProfiles(profiles);
        assertEquals("127.0.0.1", DnsChange.DOMAINS_IP_MAPS4.get("example.com."));  // 靠前赢
        assertEquals("127.0.0.1", DnsChange.DOMAINS_IP_MAPS4.get("other.com."));
    }

    @Test
    public void wildcardKeyStoredWithLeadingDot() {
        // 通配符条目 ".example.com" 存成 key ".example.com."。
        // 真正的后缀匹配发生在 handle_dns_packet（本任务不改动它），此处只验证 key 正确落库。
        List<String> profiles = new ArrayList<>();
        profiles.add("127.0.0.1 .example.com\n");
        DnsChange.loadProfiles(profiles);
        assertEquals("127.0.0.1", DnsChange.DOMAINS_IP_MAPS4.get(".example.com."));
    }

    @Test
    public void ipv4AndIpv6Separation() {
        List<String> profiles = new ArrayList<>();
        profiles.add("127.0.0.1 a.com\n2001:db8::1 b.com\n");
        DnsChange.loadProfiles(profiles);
        assertEquals("127.0.0.1", DnsChange.DOMAINS_IP_MAPS4.get("a.com."));
        assertEquals("2001:db8::1", DnsChange.DOMAINS_IP_MAPS6.get("b.com."));
        assertNull("IPv6 域名不应进入 IPv4 表", DnsChange.DOMAINS_IP_MAPS4.get("b.com."));
    }

    @Test
    public void emptyProfileListProducesEmptyMaps() {
        DnsChange.loadProfiles(new ArrayList<String>());
        assertTrue(DnsChange.DOMAINS_IP_MAPS4.isEmpty());
        assertTrue(DnsChange.DOMAINS_IP_MAPS6.isEmpty());
    }
}
