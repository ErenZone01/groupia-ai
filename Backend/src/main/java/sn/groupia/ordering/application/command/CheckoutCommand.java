package sn.groupia.ordering.application.command;

import java.math.BigDecimal;
import java.util.*;
import sn.groupia.ordering.api.dto.DeliveryAddress;

public record CheckoutCommand(Contact contact, List<Line> lines) {
    @io.swagger.v3.oas.annotations.media.Schema(name = "GroupingCheckoutContact")
    public record Contact(
            String firstName,
            String lastName,
            String phone,
            String email,
            DeliveryAddress deliveryAddress) {
        public Contact(String firstName, String lastName, String phone, String email) {
            this(firstName, lastName, phone, email, null);
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "GroupingCheckoutLine")
    public record Line(
            UUID offerId,
            Integer quantity,
            BigDecimal expectedUnitPrice,
            Long expectedPriceRevision) {}
}
