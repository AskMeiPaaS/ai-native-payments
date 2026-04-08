package com.ayedata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiNativePaymentsApplication {

    public static void main(String[] args) {
        // This launches the Spring context and initializes our LangChain4j Agents
        SpringApplication.run(AiNativePaymentsApplication.class, args);
    }

}