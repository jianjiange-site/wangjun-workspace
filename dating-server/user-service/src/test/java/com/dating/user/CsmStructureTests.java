package com.dating.user;

import com.dating.user.config.UserServiceConfig;
import com.dating.user.controller.UserController;
import com.dating.user.exception.UserServiceException;
import com.dating.user.grpc.UserCommandGrpcService;
import com.dating.user.model.UserEntity;
import com.dating.user.repository.UserRepository;
import com.dating.user.service.PhoneVerificationCodeService;
import com.dating.user.service.UserCommandService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CsmStructureTests {

    @Test
    void exposesTraditionalMvcArrangementPackages() {
        assertThat(UserController.class.getPackageName())
                .isEqualTo("com.dating.user.controller");
        assertThat(UserCommandGrpcService.class.getPackageName())
                .isEqualTo("com.dating.user.grpc");
        assertThat(UserCommandService.class.getPackageName())
                .isEqualTo("com.dating.user.service");
        assertThat(PhoneVerificationCodeService.class.getPackageName())
                .isEqualTo("com.dating.user.service");
        assertThat(UserRepository.class.getPackageName())
                .isEqualTo("com.dating.user.repository");
        assertThat(UserEntity.class.getPackageName())
                .isEqualTo("com.dating.user.model");
        assertThat(UserServiceConfig.class.getPackageName())
                .isEqualTo("com.dating.user.config");
        assertThat(UserServiceException.class.getPackageName())
                .isEqualTo("com.dating.user.exception");
    }

    @Test
    void doesNotExposeDddOrOldLayerPackages() {
        Path packageRoot = Path.of("src/main/java/com/dating/user");

        assertThat(Files.exists(packageRoot.resolve("domain"))).isFalse();
        assertThat(Files.exists(packageRoot.resolve("application"))).isFalse();
        assertThat(Files.exists(packageRoot.resolve("infrastructure"))).isFalse();
        assertThat(Files.exists(packageRoot.resolve("common"))).isFalse();
        assertThat(Files.exists(packageRoot.resolve("manager"))).isFalse();
        assertThat(Files.exists(packageRoot.resolve("mapper"))).isFalse();
        assertThat(Files.exists(packageRoot.resolve("entity"))).isFalse();
    }

    @Test
    void topLevelPackagesFollowTraditionalMvcArrangementOnly() throws Exception {
        Path packageRoot = Path.of("src/main/java/com/dating/user");

        try (var paths = Files.list(packageRoot)) {
            Set<String> packageNames = paths
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());

            assertThat(packageNames).containsExactlyInAnyOrder(
                    "controller",
                    "grpc",
                    "service",
                    "model",
                    "repository",
                    "client",
                    "config",
                    "exception"
            );
        }
    }

    @Test
    void faceScoreIsInternalAndNotReturnedToUser() {
        assertThat(Arrays.stream(UserEntity.class.getDeclaredFields()).map(field -> field.getName()))
                .contains("faceScore");
        assertThat(Arrays.stream(com.dating.user.controller.UserProfileVo.class.getDeclaredFields()).map(field -> field.getName()))
                .doesNotContain("faceScore");
    }
}
