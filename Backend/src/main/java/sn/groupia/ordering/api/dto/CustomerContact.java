package sn.groupia.ordering.api.dto;

public record CustomerContact(
        String firstName, String lastName, String phoneE164, String email, DeliveryAddress deliveryAddress) {
    public CustomerContact(String firstName, String lastName, String phoneE164, String email) {
        this(firstName, lastName, phoneE164, email, null);
    }
}
