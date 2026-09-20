package sn.groupia.ordering.application.service;
import java.util.*;
import com.google.i18n.phonenumbers.*;
import org.springframework.stereotype.Component;
import sn.groupia.grouping.api.dto.CheckoutSelection;
import sn.groupia.ordering.api.dto.CustomerContact;
import sn.groupia.ordering.application.command.CheckoutCommand;
import sn.groupia.shared.api.ApiException;

@Component
class CheckoutInput {
    String key(String value) {
        if (value == null || value.isBlank()) { throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "Une clé d'idempotence est requise."); }
        String key = value.strip();
        if (!key.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new ApiException(400, "INVALID_IDEMPOTENCY_KEY", "La clé doit contenir 16 à 128 caractères ASCII autorisés.");
        }
        return key;
    }
    CustomerContact contact(CheckoutCommand.Contact input, String country) {
        if (input == null) { throw invalidContact(); }
        String first = name(input.firstName()), last = name(input.lastName());
        String email = input.email() == null || input.email().isBlank() ? null : input.email().strip();
        if (email != null && (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || email.codePoints().anyMatch(Character::isISOControl))) { throw invalidContact(); }
        String phone = input.phone();
        if (phone == null || phone.length() > 40 || !phone.matches("\\+?[0-9() .-]+")) { throw invalidContact(); }
        var util = PhoneNumberUtil.getInstance();
        try {
            var parsed = util.parse(phone, country);
            if (parsed.hasExtension() || !util.isValidNumber(parsed)) { throw invalidContact(); }
            return new CustomerContact(first, last, util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164), email);
        } catch (NumberParseException ex) { throw invalidContact(); }
    }
    List<CheckoutSelection> lines(CheckoutCommand command) {
        if (command == null || command.lines() == null || command.lines().isEmpty() || command.lines().size() > 100) {
            throw new ApiException(400, "INVALID_ORDER_LINES", "Une commande contient entre 1 et 100 offres.");
        }
        var ids = new HashSet<UUID>();
        var lines = new ArrayList<CheckoutSelection>();
        for (var line : command.lines()) {
            if (line == null || line.offerId() == null) { throw new ApiException(400, "INVALID_ORDER_LINES", "Offre manquante."); }
            if (line.quantity() == null || line.quantity() < 1 || line.quantity() > 1000000) {
                throw new ApiException(400, "INVALID_ORDER_QUANTITY", "La quantité doit être comprise entre 1 et 1000000.");
            }
            if (!ids.add(line.offerId())) { throw new ApiException(400, "DUPLICATE_ORDER_OFFER", "Une offre ne peut apparaître qu'une fois."); }
            if (line.expectedUnitPrice() == null || line.expectedUnitPrice().signum() < 0
                    || line.expectedUnitPrice().stripTrailingZeros().scale() > 0
                    || line.expectedUnitPrice().precision() > 30 || line.expectedUnitPrice().scale() < -18
                    || line.expectedPriceRevision() == null || line.expectedPriceRevision() < 0) {
                throw new ApiException(400, "INVALID_EXPECTED_PRICE", "Prix et révision observés requis.");
            }
            lines.add(new CheckoutSelection(line.offerId(), line.quantity(), line.expectedUnitPrice().setScale(0), line.expectedPriceRevision()));
        }
        lines.sort(Comparator.comparing(line -> line.offerId().toString()));
        return List.copyOf(lines);
    }
    private String name(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 100
                || value.codePoints().anyMatch(Character::isISOControl)) { throw invalidContact(); }
        return value.strip();
    }
    private ApiException invalidContact() { return new ApiException(400, "INVALID_CUSTOMER_CONTACT", "Coordonnées client invalides."); }
}
