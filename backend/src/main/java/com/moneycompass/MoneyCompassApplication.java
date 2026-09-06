package com.moneycompass;

import com.moneycompass.config.MoneyCompassProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MoneyCompassProperties.class)
public class MoneyCompassApplication {

    public static void main(String[] args) {
        SpringApplication.run(MoneyCompassApplication.class, args);
    }
}
