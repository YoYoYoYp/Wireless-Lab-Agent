package com.njupt.wirelesslabagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WirelessLabAgentApplication {

    public static void main(String[] args) {
        System.setProperty("https.protocols", "TLSv1.2,TLSv1.3");
        SpringApplication.run(WirelessLabAgentApplication.class, args);
    }
}
