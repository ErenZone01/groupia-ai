package sn.groupia.directsales.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DirectRegionGeometryTests {
    private final DirectRegionGeometry boundaries = new DirectRegionGeometry(new ObjectMapper());

    @Test
    void recognizesSenegalRegionalLocationsWithoutAnExternalApi() {
        assertThat(boundaries.locate(14.6937, -17.4474).region()).isEqualTo("Dakar");
        assertThat(boundaries.locate(14.762, -17.395).region()).isEqualTo("Dakar");
        assertThat(boundaries.locate(14.791, -16.928).region()).isEqualTo("Thiès");
        assertThat(boundaries.locate(14.717, -17.274).region()).isEqualTo("Dakar");
        assertThat(boundaries.locate(14.6937, -17.4474).status()).isEqualTo("FOUND");
    }

    @Test
    void neverClassifiesOutsideSenegalAsOneOfItsRegions() {
        assertThat(boundaries.locate(5.3599, -4.008).status())
                .isEqualTo("OUTSIDE_SENEGAL");
        assertThat(boundaries.locate(12.639, -7.998).region()).isNull();
    }

    @Test
    void rejectsInvalidCoordinatesAndNeverGuessesRegion() {
        assertThat(boundaries.locate(100, -17.45).status()).isEqualTo("INVALID");
        assertThat(boundaries.locate(Double.NaN, -17.45).status()).isEqualTo("INVALID");
        assertThat(boundaries.locate(14.72, Double.POSITIVE_INFINITY).status())
                .isEqualTo("INVALID");
    }

    @Test
    void dataHasProvenanceForAudit() {
        assertThat(boundaries.locate(14.6937, -17.4474).source())
                .isEqualTo("geoBoundaries-SEN-ADM1-2019");
    }
}
