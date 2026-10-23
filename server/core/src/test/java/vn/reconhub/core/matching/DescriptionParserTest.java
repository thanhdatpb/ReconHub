package vn.reconhub.core.matching;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptionParserTest {

    @Test
    void findsFullCodeWithAndWithoutPrefix() {
        assertThat(DescriptionParser.parse("CK DH2610150123 NGUYEN THI LAN").codes()).containsExactly("DH2610150123");
        assertThat(DescriptionParser.parse("ck 2610150123 tien hang").codes()).containsExactly("DH2610150123");
    }

    @Test
    void ignoresBankReferencesAndPhoneNumbers() {
        assertThat(DescriptionParser.parse("FT26294518823 0912345678 MBVCB.8812347").codes()).isEmpty();
    }

    @Test
    void readsFourDigitTailsOnlyNearOrderWords() {
        assertThat(DescriptionParser.parse("tt don 0123 va 0124 LA THU UYEN").tails()).containsExactly("0123", "0124");
        assertThat(DescriptionParser.parse("NGUYEN VAN A 2026 chuyen tien").tails()).isEmpty();
    }

    @Test
    void measuresTyposAgainstOrderCode() {
        String folded = DescriptionParser.parse("CK DH2610150I23 NGUYEN THI LAN").folded();
        assertThat(DescriptionParser.closestDistance(folded, "DH2610150123")).isEqualTo(1);
        String split = DescriptionParser.parse("CK DH26101 50123").folded();
        assertThat(DescriptionParser.closestDistance(split, "DH2610150123")).isZero();
    }
}
