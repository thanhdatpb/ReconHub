package vn.reconhub.core.matching;

import org.junit.jupiter.api.Test;
import vn.reconhub.core.domain.Enums.Channel;
import vn.reconhub.core.domain.FeeComponent;
import vn.reconhub.core.domain.FeeComponent.Base;
import vn.reconhub.core.domain.FeeComponent.Condition;
import vn.reconhub.core.domain.Order;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeeFormulaTest {
    private static Order order(long amount, long cod, boolean remote) {
        Order o = new Order("DH1", Channel.WEBSITE, "Khách", amount, "GHN", Instant.now());
        o.setCodAmount(cod);
        o.setRemoteArea(remote);
        return o;
    }

    @Test
    void percentIsRoundedHalfUpAndRespectsTheMinimum() {
        var codFee = List.of(FeeComponent.percent("Phí thu hộ", "0.5", Base.COD, 5_000L, null));
        assertThat(FeeFormula.fee(codFee, order(0, 1_290_100, false), 0)).isEqualTo(6_451); // 6.450,5 rounds up
        assertThat(FeeFormula.fee(codFee, order(0, 400_000, false), 0)).isEqualTo(5_000);   // 2.000 is below the minimum
    }

    @Test
    void thresholdsAreStrictAboveAndInclusiveAtMost() {
        var surcharge = List.of(FeeComponent.fixed("Phụ phí COD lớn", 2_000, new Condition(null, Base.COD, 1_500_000L, null)));
        assertThat(FeeFormula.fee(surcharge, order(0, 1_500_000, false), 0)).isZero();
        assertThat(FeeFormula.fee(surcharge, order(0, 1_500_001, false), 0)).isEqualTo(2_000);

        var small = List.of(FeeComponent.fixed("Đơn nhỏ", 20_000, new Condition(null, Base.ORDER_AMOUNT, null, 900_000L)));
        assertThat(FeeFormula.fee(small, order(900_000, 0, false), 0)).isEqualTo(20_000);
        assertThat(FeeFormula.fee(small, order(900_001, 0, false), 0)).isZero();
    }

    @Test
    void remoteSurchargeOnlyForFlaggedOrdersAndLineGrossBase() {
        var remote = List.of(FeeComponent.fixed("Phụ phí vùng xa", 8_000, new Condition(true, null, null, null)));
        assertThat(FeeFormula.fee(remote, order(0, 0, true), 0)).isEqualTo(8_000);
        assertThat(FeeFormula.fee(remote, order(0, 0, false), 0)).isZero();

        var wallet = List.of(FeeComponent.percent("Phí ví", "1", Base.LINE_GROSS, null, null));
        assertThat(FeeFormula.fee(wallet, order(999_999, 0, false), 1_234_550)).isEqualTo(12_346);
    }
}
