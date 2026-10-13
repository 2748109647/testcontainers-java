package org.testcontainers.images.builder;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.Base58;
import org.testcontainers.utility.MockTestcontainersConfigurationExtension;
import org.testcontainers.utility.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

class ImageFromDockerfileTest {

    @ParameterizedTest(name = "globalNoCache={0}, modifierNoCache={1}, expectCacheReuse={2}")
    @CsvSource({ "false, , true", "true, , false", "true, false, true", "false, true, false" })
    @ExtendWith(MockTestcontainersConfigurationExtension.class)
    void shouldApplyLayerCachingToDockerBuilds(
        boolean globalNoCache,
        Boolean modifierNoCache,
        boolean expectCacheReuse
    ) {
        doReturn(globalNoCache).when(TestcontainersConfiguration.getInstance()).isImageFromDockerfileNoCache();

        // Each uncached RUN produces a different layer, while cached builds reuse the same image.
        String dockerfile = "FROM alpine:3.17\nRUN cat /proc/sys/kernel/random/uuid > /build-token\n";
        ImageFromDockerfile first = new ImageFromDockerfile().withFileFromString("Dockerfile", dockerfile);
        ImageFromDockerfile second = new ImageFromDockerfile().withFileFromString("Dockerfile", dockerfile);
        if (modifierNoCache != null) {
            first.withBuildImageCmdModifier(cmd -> cmd.withNoCache(modifierNoCache));
            second.withBuildImageCmdModifier(cmd -> cmd.withNoCache(modifierNoCache));
        }

        DockerClient dockerClient = DockerClientFactory.instance().client();
        String firstImageId = dockerClient.inspectImageCmd(first.resolve()).exec().getId();
        String secondImageId = dockerClient.inspectImageCmd(second.resolve()).exec().getId();

        if (expectCacheReuse) {
            assertThat(secondImageId).isEqualTo(firstImageId);
        } else {
            assertThat(secondImageId).isNotEqualTo(firstImageId);
        }
    }

    @Test
    void shouldAddDefaultLabels() {
        ImageFromDockerfile image = new ImageFromDockerfile().withDockerfileFromBuilder(it -> it.from("scratch"));

        String imageId = image.resolve();

        DockerClient dockerClient = DockerClientFactory.instance().client();

        InspectImageResponse inspectImageResponse = dockerClient.inspectImageCmd(imageId).exec();

        assertThat(inspectImageResponse.getConfig().getLabels())
            .containsAllEntriesOf(DockerClientFactory.DEFAULT_LABELS);
    }

    @Test
    void shouldNotAddSessionLabelIfDeleteOnExitIsFalse() {
        ImageFromDockerfile image = new ImageFromDockerfile(
            "localhost/testcontainers/" + Base58.randomString(16).toLowerCase(),
            false
        )
            .withDockerfileFromBuilder(it -> it.from("scratch"));
        String imageId = image.resolve();

        DockerClient dockerClient = DockerClientFactory.instance().client();

        try {
            InspectImageResponse inspectImageResponse = dockerClient.inspectImageCmd(imageId).exec();
            assertThat(inspectImageResponse.getConfig().getLabels())
                .doesNotContainKey(DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL);
        } finally {
            // ensure the image is deleted, even if the test fails
            dockerClient.removeImageCmd(imageId).exec();
        }
    }
}
