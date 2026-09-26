package com.moneycompass.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Rupee formatting has to match the frontend's, digit grouping included. */
class MoneyTest {

    @Test
    void groupsDigitsTheIndianWay() {
        assertThat(Money.inr(0)).isEqualTo("₹0");
        assertThat(Money.inr(999)).isEqualTo("₹999");
        assertThat(Money.inr(1000)).isEqualTo("₹1,000");
        assertThat(Money.inr(100000)).isEqualTo("₹1,00,000");
        assertThat(Money.inr(1234567)).isEqualTo("₹12,34,567");
        assertThat(Money.inr(123456789)).isEqualTo("₹12,34,56,789");
        assertThat(Money.inr(-2500)).isEqualTo("-₹2,500");
    }

    @Test
    void readableAmountsUseLakhAndCrore() {
        assertThat(Money.amount(420000)).isEqualTo("₹4.2 lakh");
        assertThat(Money.amount(1800000)).isEqualTo("₹18 lakh");
        assertThat(Money.amount(13500000)).isEqualTo("₹1.35 crore");
        assertThat(Money.amount(12345)).isEqualTo("₹12,500");
        assertThat(Money.amount(1234)).isEqualTo("₹1,200");
    }
}
