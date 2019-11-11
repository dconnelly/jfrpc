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
import static io.dconnelly.jfrpc.Converters.parseDuration;
import static io.dconnelly.jfrpc.Converters.parseSize;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

class ConvertersTest {
  @Test
  void duration() {
    Stream.of("30d", "30D", "30 d", "30 days")
        .forEach(d -> assertThat(parseDuration(d)).isEqualTo(Duration.ofDays(30)));
    Stream.of("30h", "30H", "30 H", "30 hours")
        .forEach(d -> assertThat(parseDuration(d)).isEqualTo(Duration.ofHours(30)));
    Stream.of("30m", "30M", "30min", "30 mins")
        .forEach(d -> assertThat(parseDuration(d)).isEqualTo(Duration.ofMinutes(30)));
    Stream.of("30s", "30S", "30sec", "30 secs")
        .forEach(d -> assertThat(parseDuration(d)).isEqualTo(Duration.ofSeconds(30)));
    assertThat(parseDuration("p2d")).isEqualTo(Duration.ofDays(2));
    assertThat(parseDuration("p2d")).isEqualTo(Duration.ofDays(2));
    assertThat(parseDuration("pt15m")).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void invalidDuration() {
    Stream.of("", "12mil", "p15m", "1234").forEach(d -> {
      try {
        parseDuration(d);
        Assert.fail("Should not have been able to convert invalid duration: " + d);
      } catch (DateTimeParseException e) {
        // Expected exception...
      }
    });
  }

  @Test
  void size() {
    Stream.of("11k", "11K", "11kb", "11KB", "11Kb", "11kB")
        .forEach(size -> assertThat(parseSize(size)).isEqualTo(11 * 1024));
    Stream.of("11m", "11M", "11mb", "11MB", "11Mb", "11mB")
        .forEach(size -> assertThat(parseSize(size)).isEqualTo(11 * 1024 * 1024));
    Stream.of("11g", "11G", "11gb", "11GB", "11Gb", "11gB", "11 gb")
        .forEach(size -> assertThat(parseSize(size)).isEqualTo(11L * 1024 * 1024 * 1024));
    assertThat(parseSize("12345")).isEqualTo(12345);
  }

  @Test
  void invalidSize() {
    Stream.of("", "11KK", "11B", "11TB").forEach(size -> {
      try {
        parseSize(size);
        Assert.fail("Should not have been able to convert invalid size: " + size);
      } catch (IllegalArgumentException e) {
        // Expected exception...
      }
    });
  }
}
