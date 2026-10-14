/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.ai.seatunnel.validation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Engine level validation with the installed SeaTunnel: HOCON parsing of SeaTunnel, installed plugins, the option
 * rules of the plugin factories and the option value types.
 *
 * <p>SeaTunnel 2.3.x {@code seatunnel.sh --check} does not validate anything, so the bundled
 * {@code SeatunnelConfigChecker} is compiled against the jars of the SeaTunnel installation on first use and run in a
 * separate JVM, the SeaTunnel classes never get into the api server.
 */
public class SeatunnelEngineChecker {

    private static final Logger log = LoggerFactory.getLogger(SeatunnelEngineChecker.class);

    private static final String CHECKER_CLASS = "SeatunnelConfigChecker";
    private static final String CHECKER_SOURCE = "/seatunnel/checker/" + CHECKER_CLASS + ".java";

    private final String seatunnelHome;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path workDir;

    private volatile Path classesDir;
    private volatile String unavailableReason;

    public SeatunnelEngineChecker(String seatunnelHome, Duration timeout) {
        this.seatunnelHome = seatunnelHome == null ? "" : seatunnelHome.trim();
        this.timeout = timeout;
        this.workDir = Paths.get(System.getProperty("java.io.tmpdir"), "dolphinscheduler-ai", "seatunnel-checker");
    }

    public boolean isEnabled() {
        return !seatunnelHome.isEmpty();
    }

    public ValidationResult check(String configText) {
        if (!isEnabled()) {
            return ValidationResult.skipped(ValidationResult.STAGE_ENGINE, "ai.seatunnel.home is not configured");
        }
        try {
            Path classes = prepare();
            if (classes == null) {
                return ValidationResult.skipped(ValidationResult.STAGE_ENGINE, unavailableReason);
            }
            return run(classes, configText);
        } catch (Exception e) {
            log.warn("SeaTunnel engine validation failed", e);
            return ValidationResult.skipped(ValidationResult.STAGE_ENGINE,
                    "engine validation failed to run: " + e.getMessage());
        }
    }

    private ValidationResult run(Path classes, String configText) throws IOException, InterruptedException {
        Path configFile = Files.createTempFile(Files.createDirectories(workDir.resolve("configs")), "job-", ".conf");
        try {
            Files.write(configFile, configText.getBytes(StandardCharsets.UTF_8));
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.add("-Xmx512m");
            command.add("-cp");
            command.add(classes + File.pathSeparator + seatunnelClassPath());
            command.add(CHECKER_CLASS);
            command.add(configFile.toString());
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            processBuilder.environment().put("SEATUNNEL_HOME", seatunnelHome);
            Process process = processBuilder.start();
            // Read stdout concurrently so that the process never blocks on a full pipe
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try (InputStream stdout = process.getInputStream()) {
                    return new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return ValidationResult.skipped(ValidationResult.STAGE_ENGINE,
                        "engine validation timed out after " + timeout.getSeconds() + "s");
            }
            try {
                return parse(output.get(10, TimeUnit.SECONDS));
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("failed to read the output of the engine validation", e);
            }
        } finally {
            Files.deleteIfExists(configFile);
        }
    }

    ValidationResult parse(String output) throws IOException {
        String json = output.lines().filter(line -> line.startsWith("{")).reduce((first, second) -> second)
                .orElseThrow(() -> new IOException("unexpected output of the engine validation: " + output));
        JsonNode result = mapper.readTree(json);
        List<ValidationIssue> issues = new ArrayList<>();
        for (JsonNode error : result.path("errors")) {
            issues.add(ValidationIssue.error(error.path("location").asText(),
                    "[" + error.path("phase").asText() + "] " + error.path("message").asText()));
        }
        return ValidationResult.of(ValidationResult.STAGE_ENGINE, issues);
    }

    /**
     * Compile the checker against the SeaTunnel jars once, the result is cached by the checker source and the
     * SeaTunnel installation.
     *
     * @return the classes directory, or null if the engine validation is not available
     */
    private synchronized Path prepare() throws IOException, NoSuchAlgorithmException {
        if (classesDir != null || unavailableReason != null) {
            return classesDir;
        }
        Path starter = Paths.get(seatunnelHome, "starter", "seatunnel-starter.jar");
        if (!Files.isRegularFile(starter)) {
            unavailableReason = "SeaTunnel starter jar not found: " + starter;
            return null;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            unavailableReason = "the api server runs on a JRE, a JDK is required to compile the SeaTunnel checker";
            return null;
        }
        byte[] source;
        try (InputStream inputStream = SeatunnelEngineChecker.class.getResourceAsStream(CHECKER_SOURCE)) {
            if (inputStream == null) {
                throw new IOException(CHECKER_SOURCE + " not found");
            }
            source = inputStream.readAllBytes();
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(source);
        digest.update(seatunnelHome.getBytes(StandardCharsets.UTF_8));
        Path target = workDir.resolve(HexFormat.of().formatHex(digest.digest()).substring(0, 16));
        Path compiled = target.resolve("classes").resolve(CHECKER_CLASS + ".class");
        if (!Files.isRegularFile(compiled)) {
            Path sourceFile = Files.createDirectories(target.resolve("src")).resolve(CHECKER_CLASS + ".java");
            Files.write(sourceFile, source);
            Files.createDirectories(target.resolve("classes"));
            int exitCode = compiler.run(null, null, null, "-nowarn", "-proc:none", "-cp", seatunnelClassPath(),
                    "-d", target.resolve("classes").toString(), sourceFile.toString());
            if (exitCode != 0) {
                unavailableReason = "failed to compile the SeaTunnel checker against " + seatunnelHome;
                return null;
            }
        }
        log.info("SeaTunnel engine validation is enabled, SEATUNNEL_HOME: {}", seatunnelHome);
        classesDir = target.resolve("classes");
        return classesDir;
    }

    /**
     * Jars are listed explicitly, the class path wildcards are not supported by the compiler API.
     */
    private String seatunnelClassPath() throws IOException {
        List<String> entries = new ArrayList<>();
        entries.add(Paths.get(seatunnelHome, "starter", "seatunnel-starter.jar").toString());
        for (String dir : List.of("lib", "connectors")) {
            Path path = Paths.get(seatunnelHome, dir);
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.list(path)) {
                    entries.addAll(files.filter(file -> file.toString().endsWith(".jar")).map(Path::toString)
                            .sorted().collect(Collectors.toList()));
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElse(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    }
}
