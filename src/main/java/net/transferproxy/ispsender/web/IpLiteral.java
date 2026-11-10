package net.transferproxy.ispsender.web;

import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses IPv4 and IPv6 literals and nothing else. The address is built from raw bytes, so user
 * input can never trigger a name lookup, even when it looks almost like an address.
 */
public final class IpLiteral {

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");
    private static final Pattern GROUP = Pattern.compile("[0-9a-fA-F]{1,4}");

    private IpLiteral() {}

    /**
     * @return the address, or null when the text is not an IPv4 or IPv6 literal
     */
    public static @Nullable InetAddress parse(final @Nullable String text) {
        if (text == null || text.isEmpty() || text.length() > 45) return null;
        byte[] bytes = text.indexOf(':') >= 0 ? ipv6(text) : ipv4(text);
        if (bytes == null) return null;
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] ipv4(final String text) {
        if (!IPV4.matcher(text).matches()) return null;
        String[] parts = text.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) bytes[i] = (byte) Integer.parseInt(parts[i]);
        return bytes;
    }

    /** Handles one {@code ::} and an optional dotted IPv4 tail. */
    private static byte[] ipv6(final String text) {
        int gap = text.indexOf("::");
        if (gap != text.lastIndexOf("::")) return null;
        List<Integer> head = groups(gap < 0 ? text : text.substring(0, gap), gap < 0);
        List<Integer> tail = gap < 0 ? List.of() : groups(text.substring(gap + 2), true);
        if (head == null || tail == null) return null;
        int zeros = 8 - head.size() - tail.size();
        if (gap < 0 ? zeros != 0 : zeros < 1) return null;
        byte[] bytes = new byte[16];
        int index = 0;
        for (int group : head) index = put(bytes, index, group);
        index += 2 * zeros;
        for (int group : tail) index = put(bytes, index, group);
        return bytes;
    }

    /** The 16-bit groups of one side of the address; null when a group is malformed. */
    private static List<Integer> groups(final String part, final boolean ipv4Allowed) {
        List<Integer> result = new ArrayList<>();
        if (part.isEmpty()) return result;
        String[] pieces = part.split(":", -1);
        for (int i = 0; i < pieces.length; i++) {
            String piece = pieces[i];
            if (piece.indexOf('.') >= 0) {
                byte[] quad = ipv4Allowed && i == pieces.length - 1 ? ipv4(piece) : null;
                if (quad == null) return null;
                result.add(((quad[0] & 0xFF) << 8) | (quad[1] & 0xFF));
                result.add(((quad[2] & 0xFF) << 8) | (quad[3] & 0xFF));
            } else if (GROUP.matcher(piece).matches()) {
                result.add(Integer.parseInt(piece, 16));
            } else {
                return null;
            }
        }
        return result;
    }

    private static int put(final byte[] bytes, final int index, final int group) {
        bytes[index] = (byte) (group >> 8);
        bytes[index + 1] = (byte) group;
        return index + 2;
    }
}
