package sn.groupia;

import org.springframework.boot.SpringApplication;

public class TestGroupiaApplication {

	public static void main(String[] args) {
		SpringApplication.from(GroupiaApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
