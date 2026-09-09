package com.example.lotterysystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LotterySystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(LotterySystemApplication.class, args);
	}

}
