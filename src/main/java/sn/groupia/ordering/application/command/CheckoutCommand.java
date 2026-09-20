package sn.groupia.ordering.application.command;
import java.math.BigDecimal;
import java.util.*;
public record CheckoutCommand(Contact contact, List<Line> lines) {
    public record Contact(String firstName, String lastName, String phone, String email) {}
    public record Line(UUID offerId, Integer quantity, BigDecimal expectedUnitPrice, Long expectedPriceRevision) {}
}
