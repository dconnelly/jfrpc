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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

final class Flows {
  private Flows() {
  }

  /*
   * Similar to SubmissionPublisher#consume but can be used with any publisher.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public static <T> CompletableFuture<Void> consume(
      Flow.Publisher<T> publisher, Consumer<? super T> consumer) {
    var future = new CompletableFuture<Void>();

    publisher.subscribe(new Flow.Subscriber<>() {
      Flow.Subscription subscription;

      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        future.whenComplete((v, e) -> subscription.cancel());
        if (!future.isDone()) {
          subscription.request(Long.MAX_VALUE);
        }
      }

      @Override
      public void onNext(T item) {
        try {
          consumer.accept(item);
        } catch (Throwable e) {
          subscription.cancel();
          future.completeExceptionally(e);
        }
      }

      @Override
      public void onError(Throwable e) {
        future.completeExceptionally(e);
      }

      @Override
      public void onComplete() {
        future.complete(null);
      }
    });

    return future;
  }
}
