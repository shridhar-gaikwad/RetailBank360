package org.retailbank360;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
        exclude = {
                org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class
        }
)
public class AccountServiceAppProcessor {

    public static void main(String[] args) {

        org.springframework.boot.SpringApplication.run(AccountServiceAppProcessor.class, args);

    }
}
