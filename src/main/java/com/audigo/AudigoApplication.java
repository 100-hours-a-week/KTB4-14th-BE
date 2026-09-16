package com.audigo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class AudigoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AudigoApplication.class, args);
    }
}
