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

import com.google.common.base.Preconditions;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

class Converters {
  private static final Pattern PATTERN = Pattern.compile("(\\d+)\\s*(\\S*)");

  static Duration parseDuration(String value) {
    var m = PATTERN.matcher(value);
    if (!m.matches()) {
      return Duration.parse(value);
    }
    long n = Long.parseLong(m.group(1));
    switch (m.group(2).toLowerCase()) {
      case "d": case "day": case "days":
        return Duration.ofDays(n);
      case "h": case "hour": case "hours":
        return Duration.ofHours(n);
      case "m": case "min": case "mins":
        return Duration.ofMinutes(n);
      case "s": case "sec": case "secs":
        return Duration.ofSeconds(n);
      default:
        throw new DateTimeParseException("Invalid duration", value, 0);
    }
  }


  static long parseSize(String value) {
    var m = PATTERN.matcher(value);
    Preconditions.checkArgument(m.matches(), "Invalid size: %s", value);
    long n = Long.parseLong(m.group(1));
    switch (m.group(2).toLowerCase()) {
      case "k": case "kb":
        return n * 1024;
      case "m": case "mb":
        return n * 1024 * 1024;
      case "g": case "gb":
        return n * 1024 * 1024 * 1024;
      case "":
        return n;
      default:
        throw new IllegalArgumentException("Invalid size: " + value);
    }
  }
}
