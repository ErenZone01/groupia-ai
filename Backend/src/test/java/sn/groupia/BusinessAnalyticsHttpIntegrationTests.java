package sn.groupia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class BusinessAnalyticsHttpIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "MANAGER", "OPERATOR", "VIEWER"})
    void authorizedRolesReadOnlyTheirOwnGrossGoodsFacts(String role) throws Exception {
        var fixture = seed(role);
        var body =
                mvc.perform(
                                get(path(fixture.org()))
                                        .with(
                                                jwt().jwt(
                                                                j ->
                                                                        j.subject(
                                                                                fixture.actor()
                                                                                        .toString()))))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.goodsSales").value(1000))
                        .andExpect(jsonPath("$.confirmedOrders").value(1))
                        .andExpect(jsonPath("$.averageBasket").value(1000))
                        .andExpect(jsonPath("$.months.length()").value(12))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("phone", "customer", "tracking", "provider", "Fixture");
        UUID other = UUID.randomUUID();
        jdbc.update(
                "insert into organizations(id,name,status,created_at,updated_at) values (?,'Other boutique','ACTIVE',now(),now())",
                other);
        mvc.perform(get(path(other)).with(jwt().jwt(j -> j.subject(fixture.actor().toString()))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(path(UUID.randomUUID()))
                                .with(jwt().jwt(j -> j.subject(fixture.actor().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousInactiveAndRevokedActorsCannotReadAnalytics() throws Exception {
        var fixture = seed("OWNER");
        mvc.perform(get(path(fixture.org()))).andExpect(status().isUnauthorized());
        jdbc.update(
                "update organization_members set status='REVOKED',revoked_at=now() where user_account_id=?",
                fixture.actor());
        entities.clear();
        mvc.perform(
                        get(path(fixture.org()))
                                .with(jwt().jwt(j -> j.subject(fixture.actor().toString()))))
                .andExpect(status().isForbidden());
        jdbc.update(
                "update organization_members set status='ACTIVE',revoked_at=null where user_account_id=?",
                fixture.actor());
        jdbc.update("update user_accounts set status='DISABLED' where id=?", fixture.actor());
        entities.clear();
        mvc.perform(
                        get(path(fixture.org()))
                                .with(jwt().jwt(j -> j.subject(fixture.actor().toString()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get(path(fixture.org()))
                                .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1", "24", "999999999999", "not-a-number"})
    void invalidPeriodsCannotCreateUnboundedQueries(String period) throws Exception {
        var fixture = seed("OWNER");
        mvc.perform(
                        get(path(fixture.org()))
                                .param("months", period)
                                .with(jwt().jwt(j -> j.subject(fixture.actor().toString()))))
                .andExpect(status().isBadRequest());
    }

    private Fixture seed(String role) {
        UUID order = new DistributionMigrationTests().seedOrder(jdbc);
        UUID org =
                jdbc.queryForObject(
                        "select organization_id from orders where id=?", UUID.class, order);
        jdbc.update("update orders set status='CONFIRMED',confirmed_at=now() where id=?", order);
        UUID actor = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at) values (?,'+221770000009','$2a$10$' || repeat('A',53),'ACTIVE',now(),now())",
                actor);
        jdbc.update(
                "insert into organization_members(id,organization_id,user_account_id,role,status,joined_at) values (?,?,?,?, 'ACTIVE',now())",
                UUID.randomUUID(),
                org,
                actor,
                role);
        return new Fixture(org, actor);
    }

    private String path(UUID org) {
        return "/api/organizations/" + org + "/analytics/business";
    }

    private record Fixture(UUID org, UUID actor) {}
}
