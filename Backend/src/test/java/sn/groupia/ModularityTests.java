package sn.groupia;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

class ModularityTests {

	@Test
	void verifiesModuleBoundaries() {
		ApplicationModules.of(GroupiaApplication.class).verify();
	}

	@Test
	void discoversAllRootModules() {
		assertThat(ApplicationModules.of(GroupiaApplication.class).stream()
				.map(module -> module.getIdentifier().toString()))
				.containsExactlyInAnyOrder("identity", "organization", "catalog", "grouping",
						"ordering", "customeraccess", "procurement", "settlement", "payment", "shipping", "notification", "media", "audit", "shared");
	}

	@Test
	void businessModulesAreClosedAndExposeOnlyTheApiNamedInterface() {
		ApplicationModules.of(GroupiaApplication.class).stream().forEach(module -> {
			assertThat(module.isOpen()).as("%s must remain closed", module.getIdentifier()).isFalse();
			if (!module.getIdentifier().toString().equals("shared")) {
				assertThat(module.getNamedInterfaces().stream()
						.filter(namedInterface -> !namedInterface.isUnnamed())
						.map(namedInterface -> namedInterface.getName()))
						.as("Public interface of %s", module.getIdentifier())
						.containsExactly("api");
				assertThat(module.getNamedInterfaces().getUnnamedInterface())
						.as("No public types at the root of %s", module.getIdentifier())
						.isEmpty();
			}
		});
	}
}
