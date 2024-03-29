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

package com.microsoft.playwright.tools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.file.FileSystems;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

abstract class Element {
  final String jsonName;
  final String jsonPath;
  final JsonElement jsonElement;
  final Element parent;

  Element(Element parent, JsonElement jsonElement) {
    this(parent, false, jsonElement);
  }

  Element(Element parent, boolean useParentJsonPath, JsonElement jsonElement) {
    this.parent = parent;
    if (jsonElement != null && jsonElement.isJsonObject()) {
      this.jsonName = jsonElement.getAsJsonObject().get("name").getAsString();
    } else {
      this.jsonName = "";
    }
    if (useParentJsonPath) {
      this.jsonPath = parent.jsonPath;
    } else {
      this.jsonPath = parent == null ? jsonName : parent.jsonPath + "." + jsonName ;
    }
    this.jsonElement = jsonElement;
  }


  TypeDefinition typeScope() {
    return parent.typeScope();
  }

  static String toTitle(String name) {
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }
}

// Represents return type of a method, type of a method param or type of a field.
class TypeRef extends Element {
  String customType;
  boolean isNestedClass;

  TypeRef(Element parent, JsonElement jsonElement) {
    super(parent, true, jsonElement);

    createCustomType();
  }

  void createCustomType() {
    boolean isEnum = jsonName.contains("|\"");
    boolean isClass = jsonName.replace("null|", "").equals("Object");
    // Use path to the corresponding method, param of field as the key.
    String parentPath = parent.jsonPath;
    Types.Mapping mapping = TypeDefinition.types.findForPath(parentPath);
    if (mapping == null) {
      if (isEnum) {
        throw new RuntimeException("Cannot create enum, type mapping is missing for: " + parentPath);
      }
      if (!isClass) {
        return;
      }

      if (parent instanceof Field) {
        customType = toTitle(parent.jsonName);
      } else {
        customType = toTitle(parent.parent.jsonName) + toTitle(parent.jsonName);
      }
    } else {
      if (!mapping.from.equals(jsonName)) {
        throw new RuntimeException("Unexpected source type for: " + parentPath +". Expected: " + mapping.from + "; found: " + jsonName);
      }
      customType = mapping.to;
      if (mapping.customMapping != null) {
        mapping.customMapping.defineTypesIn(typeScope());
        return;
      }
    }
    if (isEnum) {
      typeScope().createEnum(customType, jsonName);
    } else if (isClass) {
      typeScope().createNestedClass(customType, this, jsonElement.getAsJsonObject());
      isNestedClass = true;
    }
  }

  String toJava() {
    if (customType != null) {
      return customType;
    }
    if (jsonElement.isJsonNull()) {
      return "void";
    }
    // Convert optional fields to boxed types.
    if (!parent.jsonElement.getAsJsonObject().get("required").getAsBoolean()) {
      if (jsonName.equals("number")) {
        return "Integer";
      }
      if (jsonName.equals("boolean")) {
        return "Boolean";
      }
    }
    if (jsonName.replace("null|", "").contains("|")) {
      throw new RuntimeException("Missing mapping for type union: " + jsonPath + ": " + jsonName);
    }
    return convertBuiltinType(stripPromise(jsonName));
  }

  private static String stripPromise(String type) {
    if ("Promise".equals(type)) {
      return "void";
    }
    // Java API is sync just strip Promise<>
    if (type.startsWith("Promise<")) {
      return type.substring("Promise<".length(), type.length() - 1);
    }
    return type;
  }

  private static String convertBuiltinType(String type) {
    return type.replace("Array<", "List<")
      .replace("Object<", "Map<")
      .replace("string", "String")
      .replace("number", "int")
      .replace("null|", "");
  }
}

abstract class TypeDefinition extends Element {
  final List<Enum> enums = new ArrayList<>();
  final List<NestedClass> classes = new ArrayList<>();

  static final Types types = new Types();

  TypeDefinition(Element parent, JsonObject jsonElement) {
    super(parent, jsonElement);
  }

  TypeDefinition(Element parent, boolean useParentJsonPath, JsonObject jsonElement) {
    super(parent, useParentJsonPath, jsonElement);
  }

  @Override
  TypeDefinition typeScope() {
    return this;
  }

  void createEnum(String name, String values) {
    addEnum(new Enum(this, name, values));
  }

  void addEnum(Enum newEnum) {
    for (Enum e : enums) {
      if (e.name.equals(newEnum.name)) {
        return;
      }
    }
    enums.add(newEnum);
  }

  void createNestedClass(String name, Element parent, JsonObject jsonObject) {
    for (NestedClass c : classes) {
      if (c.name.equals(name)) {
        return;
      }
    }
    classes.add(new NestedClass(parent, name, jsonObject));
  }

  void writeTo(List<String> output, String offset) {
    for (Enum e : enums) {
      e.writeTo(output, offset);
    }
    for (NestedClass c : classes) {
      c.writeTo(output, offset);
    }
  }
}

class Event extends Element {
  private final TypeRef type;

  Event(Element parent, JsonObject jsonElement) {
    super(parent, jsonElement);
    type = new TypeRef(this, jsonElement.get("type"));
  }
}

class Method extends Element {
  final TypeRef returnType;
  final List<Param> params = new ArrayList<>();
  private final String name;

  private static Map<String, String> tsToJavaMethodName = new HashMap<>();
  static {
    tsToJavaMethodName.put("continue", "continue_");
    tsToJavaMethodName.put("$eval", "evalOnSelector");
    tsToJavaMethodName.put("$$eval", "evalOnSelectorAll");
    tsToJavaMethodName.put("$", "querySelector");
    tsToJavaMethodName.put("$$", "querySelectorAll");
    tsToJavaMethodName.put("goto", "navigate");
  }

  private static Map<String, String[]> customSignature = new HashMap<>();
  static {
    customSignature.put("Page.setViewportSize", new String[]{"void setViewportSize(int width, int height);"});
    customSignature.put("BrowserContext.setHTTPCredentials", new String[]{
      "void setHTTPCredentials(String username, String password);"});
    customSignature.put("BrowserContext.route", new String[]{
      "void route(String url, BiConsumer<Route, Request> handler);",
      "void route(Pattern url, BiConsumer<Route, Request> handler);",
      "void route(Predicate<String> url, BiConsumer<Route, Request> handler);",
    });
    customSignature.put("Page.frame", new String[]{
      "Frame frameByName(String name);",
      "Frame frameByUrl(String glob);",
      "Frame frameByUrl(Pattern pattern);",
      "Frame frameByUrl(Predicate<String> predicate);",
    });
    customSignature.put("Page.route", new String[]{
      "void route(String url, BiConsumer<Route, Request> handler);",
      "void route(Pattern url, BiConsumer<Route, Request> handler);",
      "void route(Predicate<String> url, BiConsumer<Route, Request> handler);",
    });
    customSignature.put("BrowserContext.unroute", new String[]{
      "default void unroute(String url) { unroute(url, null); }",
      "default void unroute(Pattern url) { unroute(url, null); }",
      "default void unroute(Predicate<String> url) { unroute(url, null); }",
      "void unroute(String url, BiConsumer<Route, Request> handler);",
      "void unroute(Pattern url, BiConsumer<Route, Request> handler);",
      "void unroute(Predicate<String> url, BiConsumer<Route, Request> handler);",
    });
    customSignature.put("Page.unroute", new String[]{
      "default void unroute(String url) { unroute(url, null); }",
      "default void unroute(Pattern url) { unroute(url, null); }",
      "default void unroute(Predicate<String> url) { unroute(url, null); }",
      "void unroute(String url, BiConsumer<Route, Request> handler);",
      "void unroute(Pattern url, BiConsumer<Route, Request> handler);",
      "void unroute(Predicate<String> url, BiConsumer<Route, Request> handler);",
    });
    customSignature.put("FileChooser.setFiles", new String[]{
      "default void setFiles(File file) { setFiles(file, null); }",
      "default void setFiles(File file, SetFilesOptions options) { setFiles(new File[]{ file }, options); }",
      "default void setFiles(File[] files) { setFiles(files, null); }",
      "void setFiles(File[] files, SetFilesOptions options);",
      "default void setFiles(FileChooser.FilePayload file) { setFiles(file, null); }",
      "default void setFiles(FileChooser.FilePayload file, SetFilesOptions options)  { setFiles(new FileChooser.FilePayload[]{ file }, options); }",
      "default void setFiles(FileChooser.FilePayload[] files) { setFiles(files, null); }",
      "void setFiles(FileChooser.FilePayload[] files, SetFilesOptions options);",
    });
    customSignature.put("ElementHandle.setInputFiles", new String[]{
      "default void setInputFiles(File file) { setInputFiles(file, null); }",
      "default void setInputFiles(File file, SetInputFilesOptions options) { setInputFiles(new File[]{ file }, options); }",
      "default void setInputFiles(File[] files) { setInputFiles(files, null); }",
      "void setInputFiles(File[] files, SetInputFilesOptions options);",
      "default void setInputFiles(FileChooser.FilePayload file) { setInputFiles(file, null); }",
      "default void setInputFiles(FileChooser.FilePayload file, SetInputFilesOptions options)  { setInputFiles(new FileChooser.FilePayload[]{ file }, options); }",
      "default void setInputFiles(FileChooser.FilePayload[] files) { setInputFiles(files, null); }",
      "void setInputFiles(FileChooser.FilePayload[] files, SetInputFilesOptions options);",
    });
    String[] setInputFilesWithSelector = {
      "default void setInputFiles(String selector, File file) { setInputFiles(selector, file, null); }",
      "default void setInputFiles(String selector, File file, SetInputFilesOptions options) { setInputFiles(selector, new File[]{ file }, options); }",
      "default void setInputFiles(String selector, File[] files) { setInputFiles(selector, files, null); }",
      "void setInputFiles(String selector, File[] files, SetInputFilesOptions options);",
      "default void setInputFiles(String selector, FileChooser.FilePayload file) { setInputFiles(selector, file, null); }",
      "default void setInputFiles(String selector, FileChooser.FilePayload file, SetInputFilesOptions options)  { setInputFiles(selector, new FileChooser.FilePayload[]{ file }, options); }",
      "default void setInputFiles(String selector, FileChooser.FilePayload[] files) { setInputFiles(selector, files, null); }",
      "void setInputFiles(String selector, FileChooser.FilePayload[] files, SetInputFilesOptions options);",
    };
    customSignature.put("Page.setInputFiles", setInputFilesWithSelector);
    customSignature.put("Frame.setInputFiles", setInputFilesWithSelector);

    String[] waitForEvent = {
      "default Deferred<Event<EventType>> waitForEvent(EventType event) {",
      "  return waitForEvent(event, (WaitForEventOptions) null);",
      "}",
      "default Deferred<Event<EventType>> waitForEvent(EventType event, Predicate<Event<EventType>> predicate) {",
      "  WaitForEventOptions options = new WaitForEventOptions();",
      "  options.predicate = predicate;",
      "  return waitForEvent(event, options);",
      "}",
      "Deferred<Event<EventType>> waitForEvent(EventType event, WaitForEventOptions options);",
    };
    customSignature.put("Page.waitForEvent", waitForEvent);
    customSignature.put("BrowserContext.waitForEvent", waitForEvent);
  }

  Method(TypeDefinition parent, JsonObject jsonElement) {
    super(parent, jsonElement);
    returnType = new TypeRef(this, jsonElement.get("type"));
    if (jsonElement.get("args") != null) {
      for (Map.Entry<String, JsonElement> arg : jsonElement.get("args").getAsJsonObject().entrySet()) {
        params.add(new Param(this, arg.getValue().getAsJsonObject()));
      }
    }
    name = tsToJavaMethodName.containsKey(jsonName) ? tsToJavaMethodName.get(jsonName) : jsonName;
  }

  private String toJava() {
    StringBuilder paramList = new StringBuilder();
    for (Param p : params) {
      if (paramList.length() > 0)
        paramList.append(", ");
      paramList.append(p.toJava());
    }

    return returnType.toJava() + " " + name + "(" + paramList + ");";
  }

  void writeTo(List<String> output, String offset) {
    if (customSignature.containsKey(jsonPath)) {
      for (String signature : customSignature.get(jsonPath)) {
        output.add(offset + signature);
      }
      return;
    }
    for (int i = params.size() - 1; i >= 0; i--) {
      Param p = params.get(i);
      if (!p.isOptional()) {
        break;
      }
      writeDefaultOverloadedMethod(i, output, offset);
    }
    output.add(offset + toJava());
  }

  private void writeDefaultOverloadedMethod(int paramCount, List<String> output, String offset) {
    StringBuilder paramList = new StringBuilder();
    StringBuilder argList = new StringBuilder();
    for (int i = 0; i < paramCount; i++) {
      Param p = params.get(i);
      if (paramList.length() > 0) {
        paramList.append(", ");
        argList.append(", ");
      }
      paramList.append(p.toJava());
      argList.append(p.jsonName);
    }
    if (argList.length() > 0) {
      argList.append(", ");
    }
    argList.append("int".equals(params.get(paramCount).type.toJava()) ? "0" : "null");
    String returns = returnType.toJava().equals("void") ? "" : "return ";
    output.add(offset + "default " + returnType.toJava() + " " + name + "(" + paramList + ") {");
    output.add(offset + "  " + returns + name + "(" + argList + ");");
    output.add(offset + "}");
  }
}

class Param extends Element {
  final TypeRef type;

  private static Map<String, String> customName = new HashMap<>();
  static {
    customName.put("Keyboard.type.options", "delay");
    customName.put("Keyboard.press.options", "delay");
  }

  Param(Method method, JsonObject jsonElement) {
    super(method, jsonElement);
    type = new TypeRef(this, jsonElement.get("type").getAsJsonObject());
  }

  boolean isOptional() {
    return !jsonElement.getAsJsonObject().get("required").getAsBoolean();
  }

  private String name() {
    String name = customName.get(jsonPath);
    if (name != null) {
      return name;
    }
    return jsonName;
  }

  String toJava() {
    return type.toJava() + " " + name();
  }
}

class Field extends Element {
  final String name;
  final TypeRef type;

  Field(NestedClass parent, String name, JsonObject jsonElement) {
    super(parent, jsonElement);
    this.name = name;
    this.type = new TypeRef(this, jsonElement.getAsJsonObject().get("type"));
  }

  void writeTo(List<String> output, String offset, String access) {
    if (jsonPath.contains("Frame.waitForNavigation.options.url") ||
        jsonPath.contains("Page.waitForNavigation.options.url")) {
      output.add(offset + "public String glob;");
      output.add(offset + "public Pattern pattern;");
      output.add(offset + "public Predicate<String> predicate;");
      return;
    }
    if (jsonPath.contains("Frame.waitForFunction.options.polling") ||
      jsonPath.contains("Page.waitForFunction.options.polling")) {
      output.add(offset + "public Integer pollingInterval;");
      return;
    }
    output.add(offset + access + type.toJava() + " " + name + ";");
  }

  void writeGetter(List<String> output, String offset) {
    output.add(offset + "public " + type.toJava() + " " + name + "() {");
    output.add(offset + "  return this." + name + ";");
    output.add(offset + "}");
  }

  void writeBuilderMethod(List<String> output, String offset, String parentClass) {
    if (jsonPath.contains("Frame.waitForNavigation.options.url") ||
        jsonPath.contains("Page.waitForNavigation.options.url")) {
      output.add(offset + "public WaitForNavigationOptions withUrl(String glob) {");
      output.add(offset + "  this.glob = glob;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      output.add(offset + "public WaitForNavigationOptions withUrl(Pattern pattern) {");
      output.add(offset + "  this.pattern = pattern;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      output.add(offset + "public WaitForNavigationOptions withUrl(Predicate<String> predicate) {");
      output.add(offset + "  this.predicate = predicate;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      return;
    }
    if (jsonPath.contains("Frame.waitForFunction.options.polling") ||
      jsonPath.contains("Page.waitForFunction.options.polling")) {
      output.add(offset + "public WaitForFunctionOptions withRequestAnimationFrame() {");
      output.add(offset + "  this.pollingInterval = null;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      output.add(offset + "public WaitForFunctionOptions withPollingInterval(int millis) {");
      output.add(offset + "  this.pollingInterval = millis;");
      output.add(offset + "  return this;");
      output.add(offset + "}");
      return;
    }
    if (jsonPath.equals("Route.continue.overrides.postData")) {
      output.add(offset + "public ContinueOverrides withPostData(String postData) {");
      output.add(offset + "  this.postData = postData.getBytes(StandardCharsets.UTF_8);");
      output.add(offset + "  return this;");
      output.add(offset + "}");
    }
    if (name.equals("httpCredentials")) {
      output.add(offset + "public " + parentClass + " with" + toTitle(name) + "(String username, String password) {");
      output.add(offset + "  this." + name + " = new " + type.toJava() + "(username, password);");
      output.add(offset + "  return this;");
    } else if (type.isNestedClass) {
      output.add(offset + "public " + type.toJava() + " set" + toTitle(name) + "() {");
      output.add(offset + "  this." + name + " = new " + type.toJava() + "();");
      output.add(offset + "  return this." + name + ";");
    } else if ("Page.Viewport".equals(type.toJava()) || "Viewport".equals(type.toJava())) {
      output.add(offset + "public " + parentClass + " with" + toTitle(name) + "(int width, int height) {");
      output.add(offset + "  this." + name + " = new " + type.toJava() + "(width, height);");
      output.add(offset + "  return this;");
    } else if ("Set<Keyboard.Modifier>".equals(type.toJava())) {
      output.add(offset + "public " + parentClass + " with" + toTitle(name) + "(Keyboard.Modifier... modifiers) {");
      output.add(offset + "  this." + name + " = new HashSet<>(Arrays.asList(modifiers));");
      output.add(offset + "  return this;");
    } else {
      output.add(offset + "public " + parentClass + " with" + toTitle(name) + "(" + type.toJava() + " " + name + ") {");
      output.add(offset + "  this." + name + " = " + name + ";");
      output.add(offset + "  return this;");
    }
    output.add(offset + "}");
  }
}

class Interface extends TypeDefinition {
  private final List<Method> methods = new ArrayList<>();
  private final List<Event> events = new ArrayList<>();
  private static String header = "/**\n" +
    " * Copyright (c) Microsoft Corporation.\n" +
    " *\n" +
    " * Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
    " * you may not use this file except in compliance with the License.\n" +
    " * You may obtain a copy of the License at\n" +
    " *\n" +
    " * http://www.apache.org/licenses/LICENSE-2.0\n" +
    " *\n" +
    " * Unless required by applicable law or agreed to in writing, software\n" +
    " * distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
    " * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
    " * See the License for the specific language governing permissions and\n" +
    " * limitations under the License.\n" +
    " */\n" +
    "\n" +
    "package com.microsoft.playwright;\n";

  private static Set<String> allowedBaseInterfaces = new HashSet<>(asList("Browser", "JSHandle", "BrowserContext"));

  Interface(JsonObject jsonElement) {
    super(null, jsonElement);

    JsonObject members = jsonElement.get("members").getAsJsonObject();
    for (Map.Entry<String, JsonElement> m : members.entrySet()) {
      JsonObject json = m.getValue().getAsJsonObject();
      String kind = json.get("kind").getAsString();
      if ("method".equals(kind)) {
        methods.add(new Method(this, json));
      }
      // All properties are converted to methods in Java.
      if ("property".equals(kind)) {
        methods.add(new Method(this, json));
      }
      if ("event".equals(kind)) {
        events.add(new Event(this, json));
      }
    }
  }

  void writeTo(List<String> output, String offset) {
    output.add(header);
    if (jsonName.equals("Route")) {
      output.add("import java.nio.charset.StandardCharsets;");
    }
    if (asList("Page", "Frame", "ElementHandle", "FileChooser", "ChromiumBrowser", "Route").contains(jsonName)) {
      output.add("import java.io.File;");
    }
    output.add("import java.util.*;");
    if (asList("Page", "BrowserContext").contains(jsonName)) {
      output.add("import java.util.function.BiConsumer;");
    }
    if (asList("Page", "Frame", "BrowserContext").contains(jsonName)) {
      output.add("import java.util.function.Predicate;");
      output.add("import java.util.regex.Pattern;");
    }
    output.add("");

    String implementsClause = "";
    if (jsonElement.getAsJsonObject().has("extends")) {
      String base = jsonElement.getAsJsonObject().get("extends").getAsString();
      if (allowedBaseInterfaces.contains(base)) {
        implementsClause = " extends " + base;
      }
    }

    output.add("public interface " + jsonName + implementsClause + " {");
    offset = "  ";
    writeSharedTypes(output, offset);
    writeEvents(output, offset);
    super.writeTo(output, offset);
    for (Method m : methods) {
      m.writeTo(output, offset);
    }
    if ("Worker".equals(jsonName)) {
      output.add(offset + "Deferred<Event<EventType>> waitForEvent(EventType event);");
    }
    output.add("}");
    output.add("\n");
  }

  private void writeEvents(List<String> output, String offset) {
    if (events.isEmpty()) {
      return;
    }
    output.add(offset + "enum EventType {");
    // TODO: fix api.json to not miss this event.
    if ("Page".equals(jsonName)) {
      output.add(offset + "  CLOSE,");
    }
    for (int i = 0; i < events.size(); i++) {
      String comma = i == events.size() ? "" : ",";
      output.add(offset + "  " + events.get(i).jsonName.toUpperCase() + comma);
    }
    output.add(offset + "}");
    output.add("");
    output.add(offset + "void addListener(EventType type, Listener<EventType> listener);");
    output.add(offset + "void removeListener(EventType type, Listener<EventType> listener);");
  }

  private void writeSharedTypes(List<String> output, String offset) {
    switch (jsonName) {
      case "Mouse": {
        output.add(offset + "enum Button { LEFT, MIDDLE, RIGHT }");
        output.add("");
        break;
      }
      case "Keyboard": {
        output.add(offset + "enum Modifier { ALT, CONTROL, META, SHIFT }");
        output.add("");
        break;
      }
      case "Page": {
        output.add(offset + "class Viewport {");
        output.add(offset + "  private final int width;");
        output.add(offset + "  private final int height;");
        output.add("");
        output.add(offset + "  public Viewport(int width, int height) {");
        output.add(offset + "    this.width = width;");
        output.add(offset + "    this.height = height;");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  public int width() {");
        output.add(offset + "    return width;");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  public int height() {");
        output.add(offset + "    return height;");
        output.add(offset + "  }");
        output.add(offset + "}");
        output.add("");

        output.add(offset + "interface Function {");
        output.add(offset + "  Object call(Object... args);");
        output.add(offset + "}");
        output.add("");

        output.add(offset + "interface Binding {");
        output.add(offset + "  interface Source {");
        output.add(offset + "    BrowserContext context();");
        output.add(offset + "    Page page();");
        output.add(offset + "    Frame frame();");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  Object call(Source source, Object... args);");
        output.add(offset + "}");
        output.add("");
        output.add(offset + "interface Error {");
        output.add(offset + "  String message();");
        output.add(offset + "  String name();");
        output.add(offset + "  String stack();");
        output.add(offset + "}");
        output.add("");
        break;
      }
      case "BrowserContext": {
        output.add(offset + "class HTTPCredentials {");
        output.add(offset + "  private final String username;");
        output.add(offset + "  private final String password;");
        output.add("");
        output.add(offset + "  public HTTPCredentials(String username, String password) {");
        output.add(offset + "    this.username = username;");
        output.add(offset + "    this.password = password;");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  public String username() {");
        output.add(offset + "    return username;");
        output.add(offset + "  }");
        output.add("");
        output.add(offset + "  public String password() {");
        output.add(offset + "    return password;");
        output.add(offset + "  }");
        output.add(offset + "}");
        output.add("");
        break;
      }
      case "ElementHandle": {
        output.add(offset + "class BoundingBox {");
        output.add(offset + "  public double x;");
        output.add(offset + "  public double y;");
        output.add(offset + "  public double width;");
        output.add(offset + "  public double height;");
        output.add(offset + "}");
        output.add("");
        break;
      }
      case "FileChooser": {
        output.add(offset + "class FilePayload {");
        output.add(offset + "  public final String name;");
        output.add(offset + "  public final String mimeType;");
        output.add(offset + "  public final byte[] buffer;");
        output.add("");
        output.add(offset + "  public FilePayload(String name, String mimeType, byte[] buffer) {");
        output.add(offset + "    this.name = name;");
        output.add(offset + "    this.mimeType = mimeType;");
        output.add(offset + "    this.buffer = buffer;");
        output.add(offset + "  }");
        output.add(offset + "}");
        output.add("");
        break;
      }
    }
    if (asList("Page", "BrowserContext").contains(jsonName)){
      output.add(offset + "class WaitForEventOptions {");
      output.add(offset + "  public Integer timeout;");
      output.add(offset + "  public Predicate<Event<EventType>> predicate;");

      output.add(offset + "  public WaitForEventOptions withTimeout(int millis) {");
      output.add(offset + "    timeout = millis;");
      output.add(offset + "    return this;");
      output.add(offset + "  }");
      output.add(offset + "  public WaitForEventOptions withPredicate(Predicate<Event<EventType>> predicate) {");
      output.add(offset + "    this.predicate = predicate;");
      output.add(offset + "    return this;");
      output.add(offset + "  }");
      output.add(offset + "}");
      output.add("");
    }
  }
}

class NestedClass extends TypeDefinition {
  final String name;
  final List<Field> fields = new ArrayList<>();

  NestedClass(Element parent, String name, JsonObject jsonElement) {
    super(parent, true, jsonElement);
    this.name = name;

    if (jsonElement.has("properties")) {
      JsonObject properties = jsonElement.get("properties").getAsJsonObject();
      for (Map.Entry<String, JsonElement> m : properties.entrySet()) {
        fields.add(new Field(this, m.getKey(), m.getValue().getAsJsonObject()));
      }
    }
  }

  void writeTo(List<String> output, String offset) {
    String access = parent.typeScope() instanceof NestedClass ? "public " : "";
    output.add(offset + access + "class " + name + " {");
    String bodyOffset = offset + "  ";
    super.writeTo(output, bodyOffset);

    boolean isReturnType = parent.parent instanceof Method;
    String fieldAccess = isReturnType ? "private " : "public ";
    for (Field f : fields) {
      f.writeTo(output, bodyOffset, fieldAccess);
    }
    output.add("");
    if (isReturnType) {
      for (Field f : fields) {
        f.writeGetter(output, bodyOffset);
      }
    } else {
      writeBuilderMethods(output, bodyOffset);
    }
    output.add(offset + "}");
  }

  private void writeBuilderMethods(List<String> output, String bodyOffset) {
    if (parent.typeScope() instanceof  NestedClass) {
      NestedClass outer = (NestedClass) parent.typeScope();
      output.add(bodyOffset + name + "() {");
      output.add(bodyOffset + "}");
      output.add(bodyOffset + "public " + outer.name + " done() {");
      output.add(bodyOffset + "  return " + outer.name + ".this;");
      output.add(bodyOffset + "}");
      output.add("");
    }
    for (Field f : fields) {
      f.writeBuilderMethod(output, bodyOffset, name);
    }
  }
}

class Enum extends TypeDefinition {
  final String name;
  final List<String> enumValues;

  Enum(TypeDefinition parent, String name, String values) {
    super(parent, null);
    this.name = name;
    String[] split = values.split("\\|");
    enumValues = Arrays.stream(split)
      .filter(s -> !"null".equals(s))
      .map(s -> s.substring(1, s.length() - 1).replace("-", "_").toUpperCase())
      .collect(Collectors.toList());
  }

  void writeTo(List<String> output, String offset) {
    String access = parent.typeScope() instanceof NestedClass ? "public " : "";
    output.add(offset + access + "enum " + name + " { " + String.join(", ", enumValues) + " }");
  }
}

public class ApiGenerator {
  ApiGenerator(Reader reader) throws IOException {
    JsonObject api = new Gson().fromJson(reader, JsonObject.class);
    File cwd = FileSystems.getDefault().getPath(".").toFile();
    File dir = new File(cwd, "playwright/src/main/java/com/microsoft/playwright");
    System.out.println("Writing files to: " + dir.getCanonicalPath());
    for (Map.Entry<String, JsonElement> entry: api.entrySet()) {
      String name = entry.getKey();
      List<String> lines = new ArrayList<>();
      new Interface(entry.getValue().getAsJsonObject()).writeTo(lines, "");
      String text = String.join("\n", lines);
      FileWriter writer = new FileWriter(new File(dir, name + ".java"));
      writer.write(text);
      writer.close();
    }
  }

  public static void main(String[] args) throws IOException {
    File cwd = FileSystems.getDefault().getPath(".").toFile();
    System.out.println(cwd.getCanonicalPath());
    File file = new File(cwd, "api-generator/src/main/resources/api.json");
    System.out.println("Reading from: " + file.getCanonicalPath());
    new ApiGenerator(new FileReader(file));
  }
}
