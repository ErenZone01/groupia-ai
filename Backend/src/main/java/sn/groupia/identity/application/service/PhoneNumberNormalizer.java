package sn.groupia.identity.application.service;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import org.springframework.stereotype.Component;
import sn.groupia.shared.api.ApiException;

@Component
public class PhoneNumberNormalizer implements sn.groupia.identity.api.PhoneNumbers {
    private final PhoneNumberUtil phoneNumbers = PhoneNumberUtil.getInstance();

    public String normalize(String input) {
        if (input == null || input.length() > 40 || !input.strip().matches("\\+?[0-9() .-]+")) {
            throw invalidPhone();
        }
        try {
            var number = phoneNumbers.parse(input.strip(), "SN");
            if (number.hasExtension() || !phoneNumbers.isValidNumber(number)) { throw invalidPhone(); }
            return phoneNumbers.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException ex) {
            throw invalidPhone();
        }
    }

    private ApiException invalidPhone() {
        return new ApiException(400, "INVALID_PHONE_NUMBER", "Le numéro de téléphone est invalide.");
    }
}
