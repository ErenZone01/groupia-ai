package sn.groupia;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "groupia.direct-sales.checkout.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DirectCheckoutDisabledIntegrationTests {
    @Autowired MockMvc mvc;

    @Test
    void directCheckoutRemainsClosedUntilExplicitlyEnabled() throws Exception {
        mvc.perform(
                        post("/api/public/direct-stores/" + UUID.randomUUID() + "/checkout")
                                .header("Idempotency-Key", "direct-disabled-test-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DIRECT_CHECKOUT_DISABLED"));
    }
}
