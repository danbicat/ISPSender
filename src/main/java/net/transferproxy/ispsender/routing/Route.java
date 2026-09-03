package net.transferproxy.ispsender.routing;

import net.transferproxy.ispsender.data.Server;
import org.jetbrains.annotations.NotNull;

/**
 * A resolved transfer target, with the rule that selected it.
 *
 * @param server the server to transfer the player to
 * @param rule   the matched rule, for logging (e.g. {@code AS4766}, {@code country KR} or {@code default})
 */
public record Route(@NotNull Server server, @NotNull String rule) {

}
