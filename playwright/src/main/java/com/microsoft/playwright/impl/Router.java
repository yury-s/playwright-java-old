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

package com.microsoft.playwright.impl;

import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

class Router {
  private List<RouteInfo> routes = new ArrayList<>();

  private static class RouteInfo {
    final UrlMatcher matcher;
    final BiConsumer<Route, Request> handler;

    RouteInfo(UrlMatcher matcher, BiConsumer<Route, Request> handler) {
      this.matcher = matcher;
      this.handler = handler;
    }
  }

  void add(UrlMatcher matcher, BiConsumer<Route, Request> handler) {
    routes.add(new RouteInfo(matcher, handler));
  }

  void remove(UrlMatcher matcher, BiConsumer<Route, Request> handler) {
    routes = routes.stream()
      .filter(info -> !info.matcher.equals(matcher) || (handler != null && info.handler != handler))
      .collect(Collectors.toList());
  }

  int size() {
    return routes.size();
  }

  boolean handle(Route route, Request request) {
    for (RouteInfo info : routes) {
      if (info.matcher.test(request.url())) {
        info.handler.accept(route, request);
        return true;
      }
    }
    return false;
  }
}
