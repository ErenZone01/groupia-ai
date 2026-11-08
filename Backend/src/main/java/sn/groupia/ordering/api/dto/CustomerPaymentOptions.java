package sn.groupia.ordering.api.dto;

import java.util.List;

public record CustomerPaymentOptions(List<String> availableProviders, Recovery latestAttempt) {
    public record Recovery(
            String provider, CustomerPaymentAttempt attempt, CustomerFeeQuote quote) {}
}
