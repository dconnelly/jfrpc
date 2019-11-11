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

import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import java.time.Instant;

final class Protobuf {
  private Protobuf() {
  }

  public static Duration toProto(java.time.Duration duration) {
    return Duration.newBuilder()
        .setSeconds(duration.getSeconds())
        .setNanos(duration.getNano())
        .build();
  }

  public static java.time.Duration fromProto(Duration duration) {
    return java.time.Duration.ofSeconds(duration.getSeconds(), duration.getNanos());
  }

  public static Timestamp toTimestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  public static Instant toInstant(Timestamp timestamp) {
    return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
  }
}
