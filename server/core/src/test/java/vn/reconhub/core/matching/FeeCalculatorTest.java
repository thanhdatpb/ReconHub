package vn.reconhub.core.matching;

import org.junit.jupiter.api.Test;
import vn.reconhub.core.domain.Enums.Channel;
import vn.reconhub.core.domain.Order;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FeeCalculatorTest {
    private final FeeCalculator fees = new FeeCalculator();

    private static Order cod(long amount, boolean remote) {
        Order o = new Order("DH2610150123", Channel.WEBSITE, "Khách", amount, "GHN", Instant.now());
        o.setCodAmount(amount);
        o.setRemoteArea(remote);
        return o;
    }

    @Test
    void ghnUsesMinimumCodFeeAndRemoteSurcharge() {
        assertThat(fees.expectedFee("GHN", cod(485_000, false), 0)).isEqualTo(27_000);
        assertThat(fees.expectedFee("GHN", cod(1_290_000, false), 0)).isEqualTo(28_450);
        assertThat(fees.expectedFee("GHN", cod(359_000, true), 0)).isEqualTo(35_000);
    }

    @Test
    void marketplacesRoundHalfUp() {
        Order o = new Order("DH1", Channel.SHOPEE, "Khách", 399_000, "SHOPEE", Instant.now());
        assertThat(fees.expectedFee("SHOPEE", o, 0)).isEqualTo(15_960 + 19_591);
        assertThat(fees.expectedFee("TIKTOK", o, 0)).isEqualTo(19_950);
        assertThat(fees.expectedFee("VCB", o, 0)).isEqualTo(-1);
    }
}
