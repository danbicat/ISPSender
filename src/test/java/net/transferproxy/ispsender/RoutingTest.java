package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import net.transferproxy.ispsender.config.ISPSenderConfiguration;
import net.transferproxy.ispsender.database.IPInfoRecord;
import net.transferproxy.ispsender.routing.RoutingTable;

import org.junit.jupiter.api.Test;

import java.util.*;

class RoutingTest {
    final IPInfoRecord korean =
            new IPInfoRecord("AS4766", "KT", null, "South Korea", "KR", null, null);

    private RoutingTable table() {
        var c = new ISPSenderConfiguration();
        c.setAsnServers(Map.of("AS4766", List.of("asn1.example", "asn2.example")));
        c.setCountryServers(
                Map.of(
                        "KR",
                        List.of("kr.example"),
                        "South Korea",
                        List.of("country-name.example")));
        c.setDefaultServer(List.of("default1.example", "default2.example"));
        c.setFallbackServers(List.of("backup1.example", "backup2.example"));
        return RoutingTable.build(c);
    }

    @Test
    void healthyTargetsRespectPriorityAndRecover() {
        var table = table();
        Set<String> alive =
                new HashSet<>(
                        List.of(
                                "asn2.example",
                                "kr.example",
                                "country-name.example",
                                "default2.example",
                                "backup2.example"));
        for (int i = 0; i < 100; i++)
            assertEquals(
                    "asn2.example",
                    table.resolve(korean, s -> alive.contains(s.host()), true).server().host());
        alive.remove("asn2.example");
        assertEquals(
                "kr.example",
                table.resolve(korean, s -> alive.contains(s.host()), true).server().host());
        alive.remove("kr.example");
        assertEquals(
                "country-name.example",
                table.resolve(korean, s -> alive.contains(s.host()), true).server().host());
        alive.remove("country-name.example");
        assertEquals(
                "default2.example",
                table.resolve(korean, s -> alive.contains(s.host()), true).server().host());
        alive.remove("default2.example");
        assertEquals(
                "backup2.example",
                table.resolve(korean, s -> alive.contains(s.host()), true).server().host());
        assertNull(table.resolve(korean, s -> alive.contains(s.host()), false));
        for (String backup : List.of("backup1.example", "backup2.example")) {
            assertEquals(
                    backup,
                    table.resolve(korean, s -> s.host().equals(backup), true).server().host());
        }
        alive.clear();
        assertNull(table.resolve(korean, s -> false, true));
        alive.add("asn1.example");
        assertEquals(
                "asn1.example",
                table.resolve(korean, s -> alive.contains(s.host()), true).server().host());
    }

    @Test
    void unknownOrIncompleteRecordsUseBothDefaultServers() {
        var table = table();
        assertEquals(8, table.allServers().size());
        for (String host : List.of("default1.example", "default2.example")) {
            assertEquals(
                    host, table.resolve(null, s -> s.host().equals(host), true).server().host());
            assertEquals(
                    host,
                    table.resolve(
                                    new IPInfoRecord(null, null, null, null, null, null, null),
                                    s -> s.host().equals(host),
                                    true)
                            .server()
                            .host());
        }
    }
}
