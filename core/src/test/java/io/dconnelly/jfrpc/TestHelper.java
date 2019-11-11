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
import com.google.common.truth.Correspondence;
import jdk.jfr.Configuration;
import jdk.jfr.EventType;
import jdk.jfr.SettingDescriptor;

class TestHelper {
  static Correspondence<FlightRecorderProto.Configuration, Configuration>
      configurationEquivalence() {
    return Correspondence.from(TestHelper::equivalent, "configurations equivalent");
  }

  static Correspondence<FlightRecorderProto.EventType, EventType> eventTypeEquivalence() {
    return Correspondence.from(TestHelper::equivalent, "event types equivalent");
  }

  private static boolean equivalent(FlightRecorderProto.Configuration c1, Configuration c2) {
    return c1.getName().equals(Strings.nullToEmpty(c2.getName()))
        && c1.getLabel().equals(Strings.nullToEmpty(c2.getLabel()))
        && c1.getContents().equals(Strings.nullToEmpty(c2.getContents()))
        && c1.getDescription().equals(Strings.nullToEmpty(c2.getDescription()))
        && c1.getProvider().equals(Strings.nullToEmpty(c2.getProvider()))
        && c1.getSettingsMap().equals(c2.getSettings());
  }

  private static boolean equivalent(FlightRecorderProto.EventType e1, EventType e2) {
    if (e1.getSettingDescriptorsCount() != e2.getSettingDescriptors().size()) {
      return false;
    }
    for (int i = 0; i < e1.getSettingDescriptorsCount(); i++) {
      if (!equivalent(e1.getSettingDescriptors(i), e2.getSettingDescriptors().get(i))) {
        return false;
      }
    }
    return e1.getName().equals(e2.getName())
        && e1.getLabel().equals(Strings.nullToEmpty(e2.getLabel()))
        && e1.getDescription().equals(Strings.nullToEmpty(e2.getDescription()))
        && e1.getId() == e2.getId()
        && e1.getEnabled() == e2.isEnabled()
        && e1.getCategoryNamesList().equals(e2.getCategoryNames());
  }

  private static boolean equivalent(
      FlightRecorderProto.SettingDescriptor d1, SettingDescriptor d2) {
    return d1.getName().equals(d2.getName())
        && d1.getLabel().equals(Strings.nullToEmpty(d2.getLabel()))
        && d1.getDescription().equals(Strings.nullToEmpty(d2.getDescription()))
        && d1.getContentType().equals(Strings.nullToEmpty(d2.getContentType()))
        && d1.getTypeName().equals(d2.getTypeName())
        && d1.getDefaultValue().equals(d2.getDefaultValue());
  }
}
