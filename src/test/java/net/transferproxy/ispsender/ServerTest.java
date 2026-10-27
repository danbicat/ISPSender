package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import net.transferproxy.ispsender.data.Server;

import org.junit.jupiter.api.Test;

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
}
