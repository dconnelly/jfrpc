/*
 * Copyright © 2019 David Connelly (dconnelly@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dconnelly.jfrpc;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class FlightRecorderCommandTest {
  static final Server server = ServerBuilder.forPort(0)
      .addService(new FlightRecorderService())
      .build();

  static final FlightRecorder flightRecorder = FlightRecorder.getFlightRecorder();

  @BeforeAll
  static void startServer() throws Exception {
    server.start();
  }

  @AfterAll
  static void stopServer() {
    server.shutdownNow();
  }

  @BeforeEach
  void init() {
    assertThat(flightRecorder.getRecordings()).isEmpty();
  }

  @Test
  void create() {
    var name = UUID.randomUUID().toString();
    var result = execute("create", "--name", name, "--max-size", "200m",
        "--max-age", "2h", "--duration", "4h", "--config", "default");
    result.checkSuccess();
    var json = result.json().getAsJsonObject();
    var id = json.get("id").getAsLong();
    try (var recording = findRecording(id)) {
      assertThat(json.has("settings")).isFalse();
      assertThat(recording.getState()).isEqualTo(RecordingState.NEW);
      assertThat(recording.getName()).isEqualTo(name);
      assertThat(recording.getMaxSize()).isEqualTo(200 * 1024 * 1024);
      assertThat(recording.getMaxAge()).isEqualTo(Duration.ofHours(2));
      assertThat(recording.getDuration()).isEqualTo(Duration.ofHours(4));
      assertThat(recording.getSettings()).isNotEmpty();
    }
  }

  @Test
  void start() {
    try (var recording = createRecording()) {
      var result = execute("start", String.valueOf(recording.getId()));
      result.checkSuccess();
      var json = result.json().getAsJsonObject();
      assertThat(json.get("id").getAsLong()).isEqualTo(recording.getId());
      assertThat(json.get("state").getAsString()).isEqualTo("STATE_RUNNING");
      assertThat(json.has("settings")).isFalse();
      assertThat(recording.getState()).isEqualTo(RecordingState.RUNNING);
    }
  }

  @Test
  void startDelayed() {
    try (var recording = createRecording()) {
      var result = execute("start", "--delay", "30m", String.valueOf(recording.getId()));
      result.checkSuccess();
      var json = result.json().getAsJsonObject();
      assertThat(json.get("id").getAsLong()).isEqualTo(recording.getId());
      assertThat(json.get("state").getAsString()).isEqualTo("STATE_DELAYED");
      assertThat(json.has("settings")).isFalse();
      assertThat(recording.getState()).isEqualTo(RecordingState.DELAYED);
    }
  }

  @Test
  void stop() {
    try (var recording = createRecording()) {
      recording.start();
      var result = execute("stop", String.valueOf(recording.getId()));
      result.checkSuccess();
      var json = result.json().getAsJsonObject();
      assertThat(json.get("id").getAsLong()).isEqualTo(recording.getId());
      assertThat(json.get("state").getAsString()).isEqualTo("STATE_STOPPED");
      assertThat(json.has("settings")).isFalse();
      assertThat(recording.getState()).isEqualTo(RecordingState.STOPPED);
    }
  }

  @Test
  void close() {
    try (var recording = createRecording()) {
      recording.start();
      var result = execute("close", String.valueOf(recording.getId()));
      result.checkSuccess();
      var json = result.json().getAsJsonObject();
      assertThat(json.get("id").getAsLong()).isEqualTo(recording.getId());
      assertThat(json.get("state").getAsString()).isEqualTo("STATE_CLOSED");
      assertThat(json.has("settings")).isFalse();
      assertThat(recording.getState()).isEqualTo(RecordingState.CLOSED);
    }
  }

  @Test
  void get() {
    try (var recording = createRecording()) {
      var result = execute("get", String.valueOf(recording.getId()));
      result.checkSuccess();
      var json = result.json().getAsJsonObject();
      assertThat(json.get("id").getAsLong()).isEqualTo(recording.getId());
      assertThat(json.get("state").getAsString()).isEqualTo("STATE_NEW");
      assertThat(json.get("settings").isJsonObject()).isTrue();
    }
  }

  @Test
  void snapshot() {
    var result = execute("snapshot");
    result.checkSuccess();
    var json = result.json().getAsJsonObject();
    var id = json.get("id").getAsLong();
    try (var recording = findRecording(id)) {
      assertThat(json.has("settings")).isFalse();
      assertThat(json.get("state").getAsString()).isEqualTo("STATE_STOPPED");
      assertThat(recording.getState()).isEqualTo(RecordingState.STOPPED);
    }
  }

  @Test
  void data() throws Exception {
    var file = Files.createTempFile("jfr", ".data");
    try (var recording = createRecording()) {
      recording.start();
      TimeUnit.SECONDS.sleep(2);
      recording.stop();
      var result = execute("data", String.valueOf(recording.getId()), file.toString());
      result.checkSuccess();
      assertThat(result.out).isEmpty();
      checkRecordingFile(file, recording);
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static void checkRecordingFile(Path path, Recording recording) throws IOException {
    assertThat(Files.size(path)).isEqualTo(recording.getSize());
    try (RecordingFile file = new RecordingFile(path)) {
      file.readEventTypes();
    }
  }

  @Test
  void recordings() {
    try (var recording = createRecording()) {
      recording.start();
      var result = execute("recordings");
      result.checkSuccess();
      var jsonArray = result.json().getAsJsonArray();
      assertThat(jsonArray.size()).isEqualTo(1);
      var jsonObject = jsonArray.get(0).getAsJsonObject();
      assertThat(jsonObject.get("id").getAsLong()).isEqualTo(recording.getId());
      assertThat(jsonObject.get("state").getAsString()).isEqualTo("STATE_RUNNING");
    }
  }

  @Test
  void events() {
    System.setProperty("com.google.common.truth.disable_stack_trace_cleaning", "true");
    var result = execute("events");
    result.checkSuccess();
    var jsonArray = result.json().getAsJsonArray();
    var events = flightRecorder.getEventTypes();
    assertThat(jsonArray).hasSize(events.size());
    assertThat(jsonArray.get(0).getAsJsonObject().get("id").getAsLong())
        .isEqualTo(events.get(0).getId());
  }

  @Test
  void configs() {
    var result = execute("configs");
    result.checkSuccess();
    var jsonArray = result.json().getAsJsonArray();
    var configs = Configuration.getConfigurations();
    assertThat(jsonArray).hasSize(configs.size());
  }

  private Recording findRecording(long id) {
    return flightRecorder.getRecordings().stream()
        .filter(r -> r.getId() == id)
        .findFirst()
        .orElseThrow();
  }

  private static String address() {
    return "localhost:" + server.getPort();
  }

  private static Recording createRecording() {
    return new Recording(Configuration.getConfigurations().stream()
        .filter(config -> config.getName().equals("default"))
        .findFirst()
        .orElseThrow());
  }

  private Result execute(String... args) {
    var out = new StringWriter();
    var err = new StringWriter();
    var newArgs = Stream.concat(
        Stream.of("--address", address()), Stream.of(args)).toArray(String[]::new);
    var exitCode = new CommandLine(
        new FlightRecorderCommand())
        .setOut(new PrintWriter(out, true))
        .setErr(new PrintWriter(err, true))
        .execute(newArgs);
    return new Result(exitCode, out.toString(), err.toString());
  }

  private static class Result {
    final int exitCode;
    final String out;
    final String err;

    Result(int exitCode, String out, String err) {
      this.exitCode = exitCode;
      this.out = out;
      this.err = err;
    }

    JsonElement json() {
      return JsonParser.parseString(out);
    }

    void checkSuccess() {
      assertThat(err).isEmpty();
      assertThat(exitCode).isEqualTo(0);
    }
  }
}
