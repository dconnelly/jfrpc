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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FlowsTest {
  @Test
  void consume() {
    try (var publisher = testPublisher()) {
      var results = new ArrayList<Integer>();
      var future = Flows.consume(publisher, results::add);
      assertThat(future.isDone()).isFalse();
      assertThat(publisher.getNumberOfSubscribers()).isEqualTo(1);
      IntStream.range(0, 5).forEach(publisher::submit);
      publisher.close();
      assertThat(Futures.getUnchecked(future)).isNull();
      assertThat(results).hasSize(5);
    }
  }

  @Test
  void consumeCancel() throws Exception {
    try (var publisher = testPublisher()) {
      var results = new LinkedBlockingQueue<Integer>();
      var future = Flows.consume(publisher, results::add);
      assertThat(future.isDone()).isFalse();
      publisher.submit(1);
      assertThat(results.poll(1, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(publisher.getNumberOfSubscribers()).isEqualTo(1);
      assertThat(future.cancel(true)).isTrue();
      try {
        Futures.getUnchecked(future);
        Assertions.fail("Future should have completed exceptionally");
      } catch (Throwable e) {
        assertThat(e).isInstanceOf(CancellationException.class);
      }
      assertThat(publisher.getNumberOfSubscribers()).isEqualTo(0);
    }
  }

  @Test
  void consumeError() throws Exception {
    try (var publisher = testPublisher()) {
      var results = new LinkedBlockingQueue<Integer>();
      var future = Flows.consume(publisher, results::add);
      assertThat(future.isDone()).isFalse();
      publisher.submit(1);
      assertThat(results.poll(1, TimeUnit.SECONDS)).isEqualTo(1);
      var error = new RuntimeException("error");
      publisher.closeExceptionally(error);
      try {
        future.get(1, TimeUnit.SECONDS);
        Assertions.fail("Future should have completed exceptionally");
      } catch (ExecutionException e) {
        assertThat(e).hasCauseThat().isEqualTo(error);
      }
      assertThat(publisher.isClosed()).isTrue();
    }
  }

  private static SubmissionPublisher<Integer> testPublisher() {
    return new SubmissionPublisher<>(MoreExecutors.directExecutor(), 100);
  }
}
