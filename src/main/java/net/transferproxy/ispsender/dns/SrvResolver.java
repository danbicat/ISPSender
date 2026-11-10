package net.transferproxy.ispsender.dns;

import net.transferproxy.ispsender.data.Server;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.NoInitialContextException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Finds the address a vanilla client uses for a Minecraft domain: {@code _minecraft._tcp.HOST} is
 * consulted only for port 25565 and for names, never for IP literals.
 *
 * <p>{@link #resolve(Server)} blocks on DNS, so it runs on probe threads only. Player threads call
 * {@link #cached(Server)}, which never does DNS.
 */
public final class SrvResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(SrvResolver.class);
    static final long TTL_MILLIS = 60_000L;
    private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Comparator<Record> PREFERENCE =
            Comparator.comparingInt(Record::priority)
                    .thenComparing(Comparator.comparingInt(Record::weight).reversed())
                    .thenComparing(record -> record.target() + ":" + record.port());
    private static final AtomicBoolean UNAVAILABLE_WARNED = new AtomicBoolean();
    private static final Lookup JNDI = new JndiLookup();

    /** One SRV answer: priority, weight, port and target host. */
    public record Record(int priority, int weight, int port, String target) {}

    /** The DNS query seam. The default implementation uses JNDI. */
    public interface Lookup {
        List<Record> query(String name) throws NamingException;

        /** The CNAME values of a name, empty when there are none. */
        default List<String> cname(String name) throws NamingException {
            return List.of();
        }
    }

    private record Entry(Server target, long checkedAt) {}

    private final Lookup lookup;
    private final LongSupplier nowMillis;
    private final Map<Server, Entry> cache = new ConcurrentHashMap<>();

    public SrvResolver() {
        this(JNDI, System::currentTimeMillis);
    }

    public SrvResolver(final Lookup lookup, final LongSupplier nowMillis) {
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis must not be null");
    }

    /** Whether a vanilla client would look up SRV for this server: port 25565 and a domain name. */
    public boolean eligible(final Server server) {
        final String host = server.host();
        return server.port() == Server.DEFAULT_PORT
                && host.contains(".")
                && !host.contains(":")
                && !host.matches("[0-9.]+");
    }

    /** Blocking. Call from probe threads only. */
    public Server resolve(final Server configured) {
        if (!eligible(configured)) {
            return configured;
        }
        final long now = nowMillis.getAsLong();
        final Entry known = cache.get(configured);
        if (known != null && now - known.checkedAt() < TTL_MILLIS) {
            return known.target();
        }
        final List<Record> records;
        try {
            records = usable(lookup.query("_minecraft._tcp." + configured.host()));
        } catch (final NamingException e) {
            // Transient failure: keep the last answer and do not restart the TTL.
            return known != null ? known.target() : configured;
        }
        final Server target = records.isEmpty() ? configured : toServer(records.get(0));
        cache.put(configured, new Entry(target, now));
        return target;
    }

    /** Non-blocking: the last known answer, ignoring the TTL, or the configured server. */
    public Server cached(final Server configured) {
        final Entry known = cache.get(configured);
        return known == null ? configured : known.target();
    }

    /** Whether the cached answer for this server points somewhere other than the server itself. */
    public boolean isSrv(final Server configured) {
        return !cached(configured).equals(configured);
    }

    /** Forgets servers that are no longer configured. */
    public void retain(final Set<Server> current) {
        cache.keySet().retainAll(current);
    }

    /** Uncached lookup for the panel. Usable records only, best first. */
    public List<Record> queryFresh(final String host) throws NamingException {
        return usable(lookup.query("_minecraft._tcp." + host));
    }

    /** Uncached CNAME lookup for the panel. Failures give an empty list. */
    public List<String> queryCname(final String host) {
        try {
            return lookup.cname(host);
        } catch (final NamingException e) {
            return List.of();
        }
    }

    /** Parses {@code "priority weight port target"}. Returns null for anything malformed. */
    public static @Nullable Record parseRecord(final @Nullable String value) {
        if (value == null) {
            return null;
        }
        final String[] parts = value.trim().split("\\s+");
        if (parts.length != 4) {
            return null;
        }
        try {
            final int priority = Integer.parseInt(parts[0]);
            final int weight = Integer.parseInt(parts[1]);
            final int port = Integer.parseInt(parts[2]);
            if (priority < 0 || priority > 65_535 || weight < 0 || weight > 65_535) {
                return null;
            }
            return new Record(priority, weight, port, parts[3]);
        } catch (final NumberFormatException ignored) {
            return null;
        }
    }

    /** Drops unusable answers, strips the trailing dot and sorts by preference. */
    static List<Record> usable(final List<Record> records) {
        final List<Record> result = new ArrayList<>();
        for (final Record record : records) {
            String target = record.target() == null ? "" : record.target();
            if (target.endsWith(".")) {
                target = target.substring(0, target.length() - 1);
            }
            if (target.isEmpty()
                    || target.length() > 253
                    || target.endsWith(".")
                    || target.startsWith(".")
                    || target.contains("..")
                    || !TARGET.matcher(target).matches()
                    || record.port() < 1
                    || record.port() > 65_535) {
                continue;
            }
            result.add(new Record(record.priority(), record.weight(), record.port(), target));
        }
        result.sort(PREFERENCE);
        return result;
    }

    private static Server toServer(final Record record) {
        return new Server(record.target(), record.port());
    }

    private static final class JndiLookup implements Lookup {
        @Override
        public List<Record> query(final String name) throws NamingException {
            final List<Record> records = new ArrayList<>();
            for (final String value : values(name, "SRV")) {
                final Record record = parseRecord(value);
                if (record != null) {
                    records.add(record);
                }
            }
            return records;
        }

        @Override
        public List<String> cname(final String name) throws NamingException {
            return values(name, "CNAME");
        }
    }

    /** One fresh DNS query on its own context, since DirContext is not thread safe. */
    private static List<String> values(final String name, final String type)
            throws NamingException {
        final Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("java.naming.provider.url", dnsServer());
        env.put("com.sun.jndi.dns.timeout.initial", "1000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext context = null;
        try {
            context = new InitialDirContext(env);
            final Attribute attribute = context.getAttributes(name, new String[] {type}).get(type);
            final List<String> values = new ArrayList<>();
            if (attribute != null) {
                final NamingEnumeration<?> all = attribute.getAll();
                while (all.hasMore()) {
                    values.add(String.valueOf(all.next()));
                }
            }
            return values;
        } catch (final NameNotFoundException e) {
            return List.of();
        } catch (final NoInitialContextException | NoClassDefFoundError e) {
            if (UNAVAILABLE_WARNED.compareAndSet(false, true)) {
                LOGGER.warn(
                        "JNDI DNS is unavailable ({}), SRV records are ignored",
                        e.getClass().getSimpleName());
            }
            return List.of();
        } finally {
            close(context);
        }
    }

    private static String dnsServer() {
        final String configured = System.getProperty("ispsender.dns.server");
        return configured == null || configured.isBlank() ? "dns:" : configured.trim();
    }

    private static void close(final @Nullable DirContext context) {
        if (context == null) {
            return;
        }
        try {
            context.close();
        } catch (final NamingException ignored) {
            /* Nothing to do: the context is discarded anyway. */
        }
    }
}
