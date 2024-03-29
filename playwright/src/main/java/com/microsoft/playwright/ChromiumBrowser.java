/**
 * Copyright (c) Microsoft Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.playwright;

import java.io.File;
import java.util.*;

public interface ChromiumBrowser extends Browser {
  class StartTracingOptions {
    public File path;
    public Boolean screenshots;
    public List<String> categories;

    public StartTracingOptions withPath(File path) {
      this.path = path;
      return this;
    }
    public StartTracingOptions withScreenshots(Boolean screenshots) {
      this.screenshots = screenshots;
      return this;
    }
    public StartTracingOptions withCategories(List<String> categories) {
      this.categories = categories;
      return this;
    }
  }
  CDPSession newBrowserCDPSession();
  default void startTracing(Page page) {
    startTracing(page, null);
  }
  default void startTracing() {
    startTracing(null);
  }
  void startTracing(Page page, StartTracingOptions options);
  byte[] stopTracing();
}

