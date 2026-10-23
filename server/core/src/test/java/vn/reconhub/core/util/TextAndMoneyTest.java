package vn.reconhub.core.util;

import org.junit.jupiter.api.Test;
import vn.reconhub.core.ai.Redactor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextAndMoneyTest {

    @Test
    void foldsVietnameseNames() {
        assertThat(VnText.fold("Nguyễn Thị Lan")).isEqualTo("NGUYEN THI LAN");
        assertThat(VnText.fold("Đặng Ngọc Hân")).isEqualTo("DANG NGOC HAN");
        assertThat(VnText.containsName("CK DH2610150123 NGUYEN THI LAN", "Nguyễn Thị Lan")).isTrue();
        assertThat(VnText.containsName("CK NGUYEN THI LANH", "Nguyễn Thị Lan")).isFalse();
    }

    @Test
    void parsesPartnerAmountFormats() {
        assertThat(Money.parse("1.290.000")).isEqualTo(1_290_000);
        assertThat(Money.parse("1290000")).isEqualTo(1_290_000);
        assertThat(Money.parse("1,290,000")).isEqualTo(1_290_000);
        assertThat(Money.parse("-23000")).isEqualTo(-23_000);
        assertThatThrownBy(() -> Money.parse("1.2OO.000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redactsNamesBeforeTheLlm() {
        String r = Redactor.redact("IBFT TRAN VAN HUNG ck tien hang DH2610150123", List.of("Lê Thị Mai"));
        assertThat(r).doesNotContain("TRAN").doesNotContain("HUNG").contains("DH2610150123").contains("[TÊN]");
        assertThat(Redactor.redact("CK LE THI MAI", List.of("Lê Thị Mai"))).isEqualTo("CK [TÊN KHÁCH]");
    }
}
