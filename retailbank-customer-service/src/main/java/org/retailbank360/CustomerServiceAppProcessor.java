package org.retailbank360;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

//http://localhost:8082/actuator/health                 Expected - "status": "UP"
//http://localhost:8082/swagger-ui.html

@SpringBootApplication(
        exclude = {
                org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class
        }
)
public class CustomerServiceAppProcessor {

    public static void main(String[] args) {

        SpringApplication.run(CustomerServiceAppProcessor.class, args);

    }
}
