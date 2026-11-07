package sn.groupia.payment.domain.service;

import java.math.BigDecimal;

public record ProviderFee(String country, String network, String label, BigDecimal fee) {}
