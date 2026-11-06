package org.jwcarman.noir.seal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.noir.spec.Specs;

class RepoInventoryTest {
  static final String APP_PATH = "src/main/java/com/example/shop/ShopApplication.java";
  static final String APP =
      """
      package com.example.shop;

      import org.springframework.boot.SpringApplication;
      import org.springframework.boot.autoconfigure.SpringBootApplication;

      @SpringBootApplication
      public class ShopApplication {
        public static void main(String[] args) {
          SpringApplication.run(ShopApplication.class, args);
        }
      }
      """;
  static final String ORDER_PATH = "src/main/java/com/example/shop/order/Order.java";
  static final String ORDER =
      "package com.example.shop.order;\n\npublic record Order(long id, String item) {}\n";

  static final RepoInventoryFiles SPRING_FILES =
      new RepoInventoryFiles(
          List.of(
              "org.springframework.boot:spring-boot-test:3.5.0", "org.assertj:assertj-core:3.27.3"),
          List.of("org.springframework.boot.test.context", "org.assertj.core.api"));

  @Test
  void findsTheSpringBootApplication() {
    var inventory =
        RepoInventory.of(
            SPRING_FILES,
            Map.of(APP_PATH, APP, ORDER_PATH, ORDER),
            Map.of(),
            Specs.valid(),
            List.of());

    assertThat(inventory.applicationClass()).contains("com.example.shop.ShopApplication");
    assertThat(inventory.testLibraries())
        .containsExactly(
            "org.assertj:assertj-core:3.27.3", "org.springframework.boot:spring-boot-test:3.5.0");
    assertThat(inventory.dependencyPackages())
        .containsExactlyInAnyOrder("org.springframework.boot.test.context", "org.assertj.core.api");
    assertThat(inventory.mainPackages())
        .containsExactlyInAnyOrder("com.example.shop", "com.example.shop.order");
    assertThat(inventory.repositoryApi())
        .contains("### " + APP_PATH)
        .contains("### " + ORDER_PATH)
        .doesNotContain("SpringApplication.run");
  }

  @Test
  void plainRepoAddsNoLibraryPackages() {
    var counter = "package com.example.api;\n\npublic class Counter {}\n";
    var inventory =
        RepoInventory.of(
            new RepoInventoryFiles(List.of(), List.of()),
            Map.of("src/main/java/com/example/api/Counter.java", counter),
            Map.of(),
            Specs.valid(),
            List.of());

    assertThat(inventory.testLibraries()).isEmpty();
    assertThat(inventory.dependencyPackages()).isEmpty();
    assertThat(inventory.mainPackages()).containsExactly("com.example.api");
    assertThat(inventory.applicationClass()).isEmpty();
  }

  @Test
  void twoApplicationsMeanNone() {
    var other = APP.replace("com.example.shop", "com.example.admin").replace("Shop", "Admin");
    var inventory =
        RepoInventory.of(
            SPRING_FILES,
            Map.of(APP_PATH, APP, "src/main/java/com/example/admin/AdminApplication.java", other),
            Map.of(),
            Specs.valid(),
            List.of());

    assertThat(inventory.applicationClass()).isEmpty();
  }

  @Test
  void nestedOrUnannotatedClassesAreNotTheApplication() {
    var nested =
        """
        package com.example.shop;

        public class Outer {
          @org.springframework.boot.autoconfigure.SpringBootApplication
          static class Inner {}
        }
        """;
    var qualified =
        """
        package com.example.billing;

        @org.springframework.boot.autoconfigure.SpringBootApplication
        public class BillingApplication {}
        """;
    var inventory =
        RepoInventory.of(
            SPRING_FILES,
            Map.of(
                "src/main/java/com/example/shop/Outer.java",
                nested,
                "src/main/java/com/example/billing/BillingApplication.java",
                qualified,
                ORDER_PATH,
                ORDER),
            Map.of(),
            Specs.valid(),
            List.of());

    assertThat(inventory.applicationClass()).contains("com.example.billing.BillingApplication");
  }

  @Test
  void filesAreReadFromTheInventoryDirectory(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("libraries.txt"), "org.assertj:assertj-core:3.27.3\n\n");
    Files.writeString(dir.resolve("packages.txt"), "org.assertj.core.api\norg.assertj.core.data\n");

    assertThat(RepoInventoryFiles.read(dir))
        .isEqualTo(
            new RepoInventoryFiles(
                List.of("org.assertj:assertj-core:3.27.3"),
                List.of("org.assertj.core.api", "org.assertj.core.data")));
  }
}
