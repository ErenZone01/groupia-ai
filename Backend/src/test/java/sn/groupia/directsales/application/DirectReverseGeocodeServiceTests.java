package sn.groupia.directsales.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DirectReverseGeocodeServiceTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void resolvesSenegalRegionWithoutInventingCommune() {
        var suggestion =
                DirectReverseGeocodeService.parseResponse(
                        json.readTree(
                                """
                                {"results":[{"country_code":"sn","state":"Région de Thiès",
                                  "city":"Mbour","suburb":"Saly","formatted":"Saly, Mbour, Sénégal"}]}
                                """));
        assertThat(suggestion.status()).isEqualTo("FOUND");
        assertThat(suggestion.countryCode()).isEqualTo("SN");
        assertThat(suggestion.region()).isEqualTo("Thiès");
        assertThat(suggestion.city()).isEqualTo("Mbour");
        assertThat(suggestion.neighborhood()).isEqualTo("Saly");
        assertThat(suggestion.formatted()).contains("Mbour");
    }

    @Test
    void foreignCountryNeverMapsToSenegalRegion() {
        var suggestion =
                DirectReverseGeocodeService.parseResponse(
                        json.readTree(
                                """
                                {"results":[{"country_code":"ci","state":"Dakar",
                                  "city":"Abidjan","formatted":"Abidjan, Côte d'Ivoire"}]}
                                """));
        assertThat(suggestion.countryCode()).isEqualTo("CI");
        assertThat(suggestion.region()).isNull();
        assertThat(suggestion.city()).isEqualTo("Abidjan");
    }

    @Test
    void missingFieldsAreNotInvented() {
        var suggestion =
                DirectReverseGeocodeService.parseResponse(
                        json.readTree(
                                """
                                {"results":[{"country_code":"sn"}]}
                                """));
        assertThat(suggestion.region()).isNull();
        assertThat(suggestion.city()).isNull();
        assertThat(suggestion.neighborhood()).isNull();
        assertThat(suggestion.formatted()).isNull();
    }

    @Test
    void handlesNoResult() {
        var suggestion =
                DirectReverseGeocodeService.parseResponse(
                        json.readTree(
                                """
                        {"results":[]}
                        """));
        assertThat(suggestion.status()).isEqualTo("NO_RESULT");
    }
}
