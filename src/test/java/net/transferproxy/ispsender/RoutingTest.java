package net.transferproxy.ispsender;

import static org.junit.jupiter.api.Assertions.*;

import net.transferproxy.ispsender.config.ISPSenderConfiguration;
import net.transferproxy.ispsender.data.Server;
import net.transferproxy.ispsender.database.IPInfoRecord;
import net.transferproxy.ispsender.routing.RoutingTable;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Predicate;

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
    void explainListsTheResolveOrderAndSelectsTheRuleResolveUses() {
        var table = table();
        List<Set<String>> scenarios =
                List.of(
                        Set.of(
                                "asn1.example",
                                "asn2.example",
                                "kr.example",
                                "country-name.example",
                                "default1.example",
                                "default2.example",
                                "backup1.example",
                                "backup2.example"),
                        Set.of("kr.example", "default2.example"),
                        Set.of("country-name.example", "backup2.example"),
                        Set.of("backup1.example"),
                        Set.of());
        for (Set<String> alive : scenarios) {
            Predicate<Server> available = s -> alive.contains(s.host());
            for (boolean fallback : new boolean[] {true, false}) {
                var route = table.resolve(korean, available, fallback);
                var selected =
                        table.explain(korean, available, fallback).stream()
                                .filter(RoutingTable.Step::selected)
                                .toList();
                if (route == null) {
                    assertTrue(selected.isEmpty(), alive.toString());
                } else {
                    assertEquals(1, selected.size(), alive.toString());
                    assertEquals(route.rule(), selected.get(0).rule(), alive.toString());
                }
            }
        }
        Predicate<Server> kr = s -> s.host().equals("kr.example");
        List<RoutingTable.Step> steps = table.explain(korean, kr, false);
        assertEquals(
                List.of("AS4766", "country KR", "country South Korea", "default"),
                steps.stream().map(RoutingTable.Step::rule).toList());
        assertEquals(
                List.of("asn1.example:25565", "asn2.example:25565"), steps.get(0).servers());
        assertEquals(List.of(), steps.get(0).available());
        assertEquals(List.of("kr.example:25565"), steps.get(1).available());
        assertTrue(steps.get(1).selected());
        assertFalse(steps.get(3).selected());
        List<RoutingTable.Step> noRecord = table.explain(null, s -> false, true);
        assertEquals(
                List.of("default", "fallback"),
                noRecord.stream().map(RoutingTable.Step::rule).toList());
        assertFalse(noRecord.get(0).selected());
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
