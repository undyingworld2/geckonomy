package com.the1mason.geckonomy;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Supplies Geckonomy's runtime libraries (Kotlin stdlib, coroutines, HikariCP, JDBC drivers) to the
 * plugin's isolated classloader.
 *
 * <p>Chosen over shade-relocate: Paper already isolates each plugin's classloader, so relocation
 * would buy nothing while inflating the jar from ~10 KB to ~20 MB. The trade-off is that the server
 * needs network access (or a warm library cache) the first time it starts with this plugin.
 *
 * <p><b>This class is Java on purpose — do not port it to Kotlin.</b> A PluginLoader is what declares
 * the libraries, so Paper loads it in a bootstrap classloader holding only this jar and the Paper API;
 * kotlin-stdlib does not exist yet. Kotlin emits {@code Intrinsics} null-check calls into even a
 * trivial class, so a Kotlin loader dies with {@code NoClassDefFoundError: kotlin/jvm/internal/Intrinsics}
 * before it can declare anything. Everything else in the plugin is Kotlin and runs after this succeeds.
 *
 * <p>Coordinates live in {@code geckonomy-libraries.txt} rather than here so their versions come
 * straight from the pom via resource filtering.
 */
public class GeckonomyLoader implements PluginLoader {

    private static final String LIBRARIES_RESOURCE = "/geckonomy-libraries.txt";

    @Override
    public void classloader(PluginClasspathBuilder builder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(
                new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR)
                        .build());
        for (String coordinate : libraryCoordinates()) {
            resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null));
        }
        builder.addLibrary(resolver);
    }

    private List<String> libraryCoordinates() {
        List<String> coordinates = new ArrayList<>();
        try (InputStream stream = getClass().getResourceAsStream(LIBRARIES_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        LIBRARIES_RESOURCE + " is missing from the plugin jar; the build is broken.");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    coordinates.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + LIBRARIES_RESOURCE, e);
        }
        return coordinates;
    }
}
