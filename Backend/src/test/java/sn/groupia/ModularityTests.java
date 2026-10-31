package sn.groupia;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of(GroupiaApplication.class).verify();
    }

    @Test
    void discoversAllRootModules() {
        assertThat(
                        ApplicationModules.of(GroupiaApplication.class).stream()
                                .map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder(
                        "identity",
                        "organization",
                        "catalog",
                        "grouping",
                        "ordering",
                        "directsales",
                        "customeraccess",
                        "procurement",
                        "settlement",
                        "subscription",
                        "payment",
                        "shipping",
                        "notification",
                        "media",
                        "audit",
                        "shared");
    }

    @Test
    void businessModulesAreClosedAndExposeOnlyTheApiNamedInterface() {
        ApplicationModules.of(GroupiaApplication.class).stream()
                .forEach(
                        module -> {
                            assertThat(module.isOpen())
                                    .as("%s must remain closed", module.getIdentifier())
                                    .isFalse();
                            if (!module.getIdentifier().toString().equals("shared")) {
                                assertThat(
                                                module.getNamedInterfaces().stream()
                                                        .filter(
                                                                namedInterface ->
                                                                        !namedInterface.isUnnamed())
                                                        .map(
                                                                namedInterface ->
                                                                        namedInterface.getName()))
                                        .as("Public interface of %s", module.getIdentifier())
                                        .containsExactly("api");
                                assertThat(module.getNamedInterfaces().getUnnamedInterface())
                                        .as(
                                                "No public types at the root of %s",
                                                module.getIdentifier())
                                        .isEmpty();
                            }
                        });
    }
}
