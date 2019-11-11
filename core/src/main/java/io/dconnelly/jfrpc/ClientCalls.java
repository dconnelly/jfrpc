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

import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

final class ClientCalls {
  private ClientCalls() {
  }

  /*
   * Returns a new flow publisher for executing server streaming client calls.
   * A new client call is created for each new subscription, and the StreamObserver
   * onNext/onComplete/onError methods delegate to the corresponding subscriber methods.
   */
  public static <ReqT, RespT> Flow.Publisher<RespT> serverStreamingCall(
      Supplier<ClientCall<ReqT, RespT>> callSupplier, ReqT request) {
    return subscriber -> {
      var call = callSupplier.get();
      call.start(new ClientCall.Listener<>() {
        @Override
        public void onMessage(RespT message) {
          subscriber.onNext(message);
        }

        @Override
        public void onClose(Status status, Metadata trailers) {
          if (status.isOk()) {
            subscriber.onComplete();
          } else {
            subscriber.onError(status.asRuntimeException(trailers));
          }
        }
      }, new Metadata());
      try {
        call.sendMessage(request);
        call.halfClose();
      } catch (Throwable e) {
        call.cancel(null, e);
      }
      subscriber.onSubscribe(new Flow.Subscription() {
        @Override
        public void request(long n) {
          call.request((int) Long.min(n, Integer.MAX_VALUE));
        }

        @Override
        public void cancel() {
          call.cancel("Cancelled by subscriber", null);
        }
      });
    };
  }
}
