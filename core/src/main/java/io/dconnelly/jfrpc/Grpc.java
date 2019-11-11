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

import com.google.common.base.Strings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

final class Grpc {
  private Grpc() {
  }

  public static StatusRuntimeException invalidArgument(String description) {
    return Status.INVALID_ARGUMENT.withDescription(description).asRuntimeException();
  }

  public static StatusRuntimeException failedPrecondition(String description, Throwable cause) {
    return Status.FAILED_PRECONDITION
        .withDescription(description).withCause(cause).asRuntimeException();
  }

  public static StatusRuntimeException notFound(String description) {
    return Status.NOT_FOUND.withDescription(description).asRuntimeException();
  }

  public static boolean isCancelled(Throwable e) {
    return Status.fromThrowable(e).getCode() == Status.Code.CANCELLED;
  }

  public static void checkArgument(boolean expression, String template, Object... args) {
    if (!expression) {
      throw invalidArgument(Strings.lenientFormat(template, args));
    }
  }

  public static boolean isServerError(Status.Code code) {
    switch (code) {
      case ABORTED:
      case DATA_LOSS:
      case DEADLINE_EXCEEDED:
      case INTERNAL:
      case RESOURCE_EXHAUSTED:
      case UNAVAILABLE:
      case UNIMPLEMENTED:
      case UNKNOWN:
        return true;
      default:
        return false;
    }
  }
}
