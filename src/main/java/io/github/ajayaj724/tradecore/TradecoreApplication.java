package io.github.ajayaj724.tradecore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "tradecore")
@SpringBootApplication
public class TradecoreApplication {

  public static void main(String[] args) {
    SpringApplication.run(TradecoreApplication.class, args);
  }
}
