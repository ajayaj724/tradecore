package io.github.ajayaj724.tradecore.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UpstoxClientConversionTest {

    @Test
    void convertsRupeeDecimalToPaiseHalfEven() {
        assertThat(UpstoxClient.toPaise(new BigDecimal("303.9"))).isEqualTo(30390L);
        assertThat(UpstoxClient.toPaise(new BigDecimal("100.00"))).isEqualTo(10000L);
        assertThat(UpstoxClient.toPaise(new BigDecimal("0.005"))).isEqualTo(0L); // half-even -> 0
        assertThat(UpstoxClient.toPaise(new BigDecimal("0.015"))).isEqualTo(2L); // half-even -> 2
    }
}
