package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import net.transferproxy.ispsender.data.Server;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class ServerTest {
    @Test
    void parsesIpv6AndRejectsMalformedTargets() {
        assertEquals("[::1]:25566", Server.parse("[::1]:25566").toString());
        assertEquals(25565, Server.parse("[::1]").port());
        assertEquals("host.example:25565", Server.parse("host.example").toString());
        for (String value :
                new String[] {
                    "::1",
                    "[::1]bad",
                    "host name:25565",
                    "host:0",
                    "host:65536",
                    "https://host",
                    "[]:3"
                }) assertNull(Server.parse(value), value);
    }

    @Test
    void bracketedNamesAreRejectedWithoutADnsLookup() {
        // Only IPv6 literals may be bracketed. A name would need a lookup that could block a check.
        assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> {
                    assertNull(Server.parse("[some-name.example]"));
                    assertNull(Server.parse("[some-name.example]:25566"));
                    assertNull(Server.parse("[1.2.3.4]"));
                });
    }
}
