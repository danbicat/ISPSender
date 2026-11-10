package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import net.transferproxy.ispsender.web.IpLiteral;

import org.junit.jupiter.api.Test;

import java.util.List;

class IpLiteralTest {
    @Test
    void parsesIpLiteralsAndRejectsNamesAndMalformedAddresses() {
        assertEquals("1.2.3.4", IpLiteral.parse("1.2.3.4").getHostAddress());
        assertEquals("0.0.0.0", IpLiteral.parse("0.0.0.0").getHostAddress());
        assertTrue(IpLiteral.parse("::1").isLoopbackAddress());
        assertEquals("2001:db8:0:0:0:0:0:1", IpLiteral.parse("2001:db8::1").getHostAddress());
        assertEquals("1.2.3.4", IpLiteral.parse("::ffff:1.2.3.4").getHostAddress());
        assertEquals("1:2:3:4:5:6:7:8", IpLiteral.parse("1:2:3:4:5:6:7:8").getHostAddress());
        for (String bad :
                List.of(
                        "",
                        "example.com",
                        "localhost",
                        "1.2.3",
                        "256.1.1.1",
                        "01.2.3.4",
                        "1.2.3.4.5",
                        " 1.2.3.4",
                        "1:2:3",
                        "1::2::3",
                        ":::",
                        "12345::",
                        "g::1",
                        "1.2.3.4::",
                        "::1.2.3.4.5",
                        "fe80::1%eth0",
                        "1:2:3:4:5:6:7:8:9")) {
            assertNull(IpLiteral.parse(bad), bad);
        }
        assertNull(IpLiteral.parse(null));
    }
}
