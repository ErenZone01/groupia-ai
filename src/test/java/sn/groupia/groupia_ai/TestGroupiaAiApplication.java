package sn.groupia.groupia_ai;

import org.springframework.boot.SpringApplication;

public class TestGroupiaAiApplication {

	public static void main(String[] args) {
		SpringApplication.from(GroupiaAiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
