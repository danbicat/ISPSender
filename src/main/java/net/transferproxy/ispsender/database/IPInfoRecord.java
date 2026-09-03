package net.transferproxy.ispsender.database;

import com.maxmind.db.MaxMindDbConstructor;
import com.maxmind.db.MaxMindDbParameter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A record of the IPinfo Lite database.
 * <p>
 * Every field may be null: the database does not cover every single address.
 */
public record IPInfoRecord(@Nullable String asn, @Nullable String asName, @Nullable String asDomain, @Nullable String country,
                           @Nullable String countryCode, @Nullable String continent, @Nullable String continentCode) {

    /**
     * Returned by {@link #asnNumber()} when the record carries no usable ASN.
     */
    public static final long UNKNOWN_ASN = -1L;

    @MaxMindDbConstructor
    public IPInfoRecord(final @MaxMindDbParameter(name = "asn") @Nullable String asn,
                        final @MaxMindDbParameter(name = "as_name") @Nullable String asName,
                        final @MaxMindDbParameter(name = "as_domain") @Nullable String asDomain,
                        final @MaxMindDbParameter(name = "country") @Nullable String country,
                        final @MaxMindDbParameter(name = "country_code") @Nullable String countryCode,
                        final @MaxMindDbParameter(name = "continent") @Nullable String continent,
                        final @MaxMindDbParameter(name = "continent_code") @Nullable String continentCode) {
        this.asn = asn;
        this.asName = asName;
        this.asDomain = asDomain;
        this.country = country;
        this.countryCode = countryCode;
        this.continent = continent;
        this.continentCode = continentCode;
    }

    /**
     * @return the ASN of this record as a number, or {@link #UNKNOWN_ASN} when it is missing or malformed
     */
    public long asnNumber() {
        return parseAsn(this.asn);
    }

    /**
     * Parses an ASN written as {@code AS4766}, {@code as4766} or {@code 4766}.
     *
     * @param value the raw ASN
     *
     * @return the ASN number, or {@link #UNKNOWN_ASN} when it cannot be parsed
     */
    public static long parseAsn(final @Nullable String value) {
        if (value == null) {
            return UNKNOWN_ASN;
        }
        String trimmed = value.trim();
        if (trimmed.regionMatches(true, 0, "AS", 0, 2)) {
            trimmed = trimmed.substring(2);
        }
        if (trimmed.isEmpty()) {
            return UNKNOWN_ASN;
        }
        try {
            final long asn = Long.parseLong(trimmed);
            return asn < 0 ? UNKNOWN_ASN : asn;
        } catch (final NumberFormatException ignored) {
            return UNKNOWN_ASN;
        }
    }

    /**
     * @return a short human-readable description, used for logging
     */
    public @NotNull String describe() {
        return (this.asn != null ? this.asn : "unknown ASN") + " (" + (this.asName != null ? this.asName : "?") + ") / " +
                (this.countryCode != null ? this.countryCode : "??") + " (" + (this.country != null ? this.country : "?") + ")";
    }

}
