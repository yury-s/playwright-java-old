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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.microsoft.playwright.Keyboard.Modifier.SHIFT;
import static com.microsoft.playwright.Mouse.Button.RIGHT;
import static com.microsoft.playwright.Page.EventType.CONSOLE;
import static org.junit.jupiter.api.Assertions.*;

public class TestClick extends TestBase {

  @Test
  void shouldClickTheButton() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.click("button");
    assertEquals("Clicked", page.evaluate("result"));
  }

  @Test
  void shouldClickSvg() {
    page.setContent("<svg height='100' width='100'>\n" +
      "<circle onclick='javascript:window.__CLICKED=42' cx='50' cy='50' r='40' stroke='black' stroke-width='3' fill='red'/>\n" +
      "</svg>\n");
    page.click("circle");
    assertEquals(42, page.evaluate("__CLICKED"));
  }

  @Test
  void shouldClickTheButtonIfWindowNodeIsRemoved() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.evaluate("() => delete window.Node");
    page.click("button");
    assertEquals("Clicked", page.evaluate("result"));
  }

  // @see https://github.com/GoogleChrome/puppeteer/issues/4281
  @Test
  void shouldClickOnASpanWithAnInlineElementInside() {
    page.setContent(
      "<style>\n" +
        "  span::before {\n" +
        "    content: 'q';\n" +
        "  }\n" +
        "</style>\n" +
        "<span onclick='javascript:window.CLICKED=42'></span>\n");
    page.click("span");
    assertEquals(42, page.evaluate("CLICKED"));
  }

  // TODO: it('should not throw UnhandledPromiseRejection when page closes'

  @Test
  void shouldClickThe1x1Div() {
    page.setContent("<div style='width: 1px; height: 1px;' onclick='window.__clicked = true'></div>");
    page.click("div");
    assertTrue((Boolean) page.evaluate("window.__clicked"));
  }

  @Test
  void shouldClickTheButtonAfterNavigation() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.click("button");
    page.navigate(server.PREFIX + "/input/button.html");
    page.click("button");
    assertEquals("Clicked", page.evaluate("result"));
  }

  @Test
  void shouldClickTheButtonAfterACrossOriginNavigation() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.click("button");
    page.navigate(server.CROSS_PROCESS_PREFIX + "/input/button.html");
    page.click("button");
    assertEquals("Clicked", page.evaluate("result"));
  }

  // TODO: it('should click with disabled javascript'

  @Test
  void shouldClickWhenOneOfInlineBoxChildrenIsOutsideOfViewport() {
    page.setContent(
      "<style>\n" +
        "i {\n" +
        "  position: absolute;\n" +
        "  top: -1000px;\n" +
        "}\n" +
        "</style>\n" +
        "<span onclick='javascript:window.CLICKED = 42;'><i>woof</i><b>doggo</b></span>\n");
    page.click("span");
    assertEquals(42, page.evaluate("CLICKED"));
  }

  @Test
  void shouldSelectTheTextByTripleClicking() {
    page.navigate(server.PREFIX + "/input/textarea.html");
    String text = "This is the text that we are going to try to select. Let's see how it goes.";
    page.fill("textarea", text);
    page.click("textarea", new Page.ClickOptions().withClickCount(3));
    assertEquals(text, page.evaluate("() => {\n" +
      "  const textarea = document.querySelector('textarea');\n" +
      "  return textarea.value.substring(textarea.selectionStart, textarea.selectionEnd);\n" +
      "}"));
  }

  @Test
  void shouldClickOffscreenButtons() {
    page.navigate(server.PREFIX + "/offscreenbuttons.html");
    List<String> messages = new ArrayList<>();
    page.addListener(CONSOLE, event -> messages.add(((ConsoleMessage) event.data()).text()));
    for (int i = 0; i < 11; ++i) {
      // We might've scrolled to click a button - reset to (0, 0).
      page.evaluate("() => window.scrollTo(0, 0)");
      page.click("#btn" + i);
    }
    assertEquals(Arrays.asList(
      "button #0 clicked",
      "button #1 clicked",
      "button #2 clicked",
      "button #3 clicked",
      "button #4 clicked",
      "button #5 clicked",
      "button #6 clicked",
      "button #7 clicked",
      "button #8 clicked",
      "button #9 clicked",
      "button #10 clicked"
    ), messages);
  }

  @Test
  void shouldWaitForVisibleWhenAlreadyVisible() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.click("button");
    assertEquals("Clicked", page.evaluate("result"));
  }

  @Test
  void shouldNotWaitWithForce() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.evalOnSelector("button", "b => b.style.display = 'none'");
    Exception exception = null;
    try {
      page.click("button", new Page.ClickOptions().withForce(true));
    } catch (RuntimeException e) {
      exception = e;
    }
    assertNotNull(exception);
    assertTrue(exception.getMessage().contains("Element is not visible"));
    assertEquals("Was not clicked", page.evaluate("result"));
  }

  // TODO: not supported in sync api
  void shouldWaitForDisplayNoneToBeGone() {
  }

  void shouldWaitForVisibilityHiddenToBeGone() {
  }

  void shouldWaitForVisibleWhenParentIsHidden() {
  }

  @Test
  void shouldClickWrappedLinks() {
    page.navigate(server.PREFIX + "/wrappedlink.html");
    page.click("a");
    assertTrue((Boolean) page.evaluate("__clicked"));
  }

  @Test
  void shouldClickOnCheckboxInputAndToggle() {
    page.navigate(server.PREFIX + "/input/checkbox.html");
    assertNull(page.evaluate("() => window['result'].check"));
    page.click("input#agree");
    assertTrue((Boolean) page.evaluate("() => window['result'].check"));
    assertEquals(Arrays.asList(
      "mouseover",
      "mouseenter",
      "mousemove",
      "mousedown",
      "mouseup",
      "click",
      "input",
      "change"),
      page.evaluate("() => window['result'].events"));
    page.click("input#agree");
    assertFalse((Boolean) page.evaluate("() => window['result'].check"));
  }

  @Test
  void shouldClickOnCheckboxLabelAndToggle() {
    page.navigate(server.PREFIX + "/input/checkbox.html");
    assertNull(page.evaluate("() => window['result'].check"));
    page.click("label[for='agree']");
    assertTrue((Boolean) page.evaluate("() => window['result'].check"));
    assertEquals(Arrays.asList(
      "click",
      "input",
      "change"),
      page.evaluate("() => window['result'].events"));
    page.click("label[for='agree']");
    assertFalse((Boolean) page.evaluate("() => window['result'].check"));
  }

  @Test
  void shouldNotHangWithTouchEnabledViewports() {
    // @see https://github.com/GoogleChrome/puppeteer/issues/161
    DeviceDescriptor descriptor = playwright.devices().get("iPhone 6");
    BrowserContext context = browser.newContext(new Browser.NewContextOptions()
      .withViewport(descriptor.viewport().width(), descriptor.viewport().height())
      .withHasTouch(descriptor.hasTouch()));
    Page page = context.newPage();
    page.mouse().down();
    page.mouse().move(100, 10);
    page.mouse().up();
    context.close();
  }

  @Test
  void shouldScrollAndClickTheButton() {
    page.navigate(server.PREFIX + "/input/scrollable.html");
    page.click("#button-5");
    assertEquals("clicked", page.evaluate("() => document.querySelector('#button-5').textContent"));
    page.click("#button-80");
    assertEquals("clicked", page.evaluate("() => document.querySelector('#button-80').textContent"));
  }

  @Test
  void shouldDoubleClickTheButton() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.evaluate("() => {\n" +
      "  window['double'] = false;\n" +
      "  const button = document.querySelector('button');\n" +
      "  button.addEventListener('dblclick', event => {\n" +
      "    window['double'] = true;\n" +
      "  });\n" +
      "}");
    page.dblclick("button");
    assertEquals(true, page.evaluate("double"));
    assertEquals("Clicked", page.evaluate("result"));
  }

  @Test
  void shouldClickAPartiallyObscuredButton() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.evaluate("() => {\n" +
      "  const button = document.querySelector('button');\n" +
      "  button.textContent = 'Some really long text that will go offscreen';\n" +
      "  button.style.position = 'absolute';\n" +
      "  button.style.left = '368px';\n" +
      "}");
    page.click("button");
    assertEquals("Clicked", page.evaluate("() => window['result']"));
  }

  @Test
  void shouldClickARotatedButton() {
    page.navigate(server.PREFIX + "/input/rotatedButton.html");
    page.click("button");
    assertEquals("Clicked", page.evaluate("result"));
  }

  @Test
  void shouldFireContextmenuEventOnRightClick() {
    page.navigate(server.PREFIX + "/input/scrollable.html");
    page.click("#button-8", new Page.ClickOptions().withButton(RIGHT));
    assertEquals("context menu", page.evaluate("() => document.querySelector('#button-8').textContent"));
  }

  @Test
  void shouldClickLinksWhichCauseNavigation() {
    // @see https://github.com/GoogleChrome/puppeteer/issues/206
    page.setContent("<a href=" + server.EMPTY_PAGE + ">empty.html</a>");
    // This should not hang.
    page.click("a");
  }

  // TODO: support element handle
  void shouldClickTheButtonInsideAnIframe() {
  }

  // TODO: do we need it in java?
  //  void shouldClickTheButtonWithFixedPositionInsideAnIframe() {
  //    test.fixme(browserName === "chromium" || browserName === "webkit");


  // TODO: support element handle
  void shouldClickTheButtonWithDeviceScaleFactorSet() {
  }

  @Test
  void shouldClickTheButtonWithPxBorderWithOffset() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.evalOnSelector("button", "button => button.style.borderWidth = '8px'");
    page.click("button", new Page.ClickOptions().setPosition().withX(20).withY(10).done());
    assertEquals(page.evaluate("result"), "Clicked");
    // Safari reports border-relative offsetX/offsetY.
    assertEquals(isWebKit ? 20 + 8 : 20, page.evaluate("offsetX"));
    assertEquals(isWebKit ? 10 + 8 : 10, page.evaluate("offsetY"));
  }

  @Test
  void shouldClickTheButtonWithEmBorderWithOffset() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.evalOnSelector("button", "button => button.style.borderWidth = '2em'");
    page.evalOnSelector("button", "button => button.style.fontSize = '12px'");
    page.click("button", new Page.ClickOptions().setPosition().withX(20).withY(10).done());
    assertEquals("Clicked", page.evaluate("result"));
    // Safari reports border-relative offsetX/offsetY.
    assertEquals(isWebKit ? 12 * 2 + 20 : 20, page.evaluate("offsetX"));
    assertEquals(isWebKit ? 12 * 2 + 10 : 10, page.evaluate("offsetY"));
  }

  @Test
  void shouldClickAVeryLargeButtonWithOffset() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.evalOnSelector("button", "button => button.style.borderWidth = '8px'");
    page.evalOnSelector("button", "button => button.style.height = button.style.width = '2000px'");
    page.click("button", new Page.ClickOptions().setPosition().withX(1900).withY(1910).done());
    assertEquals("Clicked", page.evaluate("() => window['result']"));
    // Safari reports border-relative offsetX/offsetY.
    assertEquals(isWebKit ? 1900 + 8 : 1900, page.evaluate("offsetX"));
    assertEquals(isWebKit ? 1910 + 8 : 1910, page.evaluate("offsetY"));
  }

  @Test
  void shouldClickAButtonInScrollingContainerWithOffset() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.evalOnSelector("button", "button => {\n" +
      "  const container = document.createElement('div');\n" +
      "  container.style.overflow = 'auto';\n" +
      "  container.style.width = '200px';\n" +
      "  container.style.height = '200px';\n" +
      "  button.parentElement.insertBefore(container, button);\n" +
      "  container.appendChild(button);\n" +
      "  button.style.height = '2000px';\n" +
      "  button.style.width = '2000px';\n" +
      "  button.style.borderWidth = '8px';\n" +
      "}");
    page.click("button", new Page.ClickOptions().setPosition().withX(1900).withY(1910).done());
    assertEquals("Clicked", page.evaluate("() => window['result']"));
    // Safari reports border-relative offsetX/offsetY.
    assertEquals(isWebKit ? 1900 + 8 : 1900, page.evaluate("offsetX"));
    assertEquals(isWebKit ? 1910 + 8 : 1910, page.evaluate("offsetY"));
  }

  @Test
  void shouldClickTheButtonWithOffsetWithPageScale() {
    // TODO:    test.skip(browserName === "firefox");
    BrowserContext context = browser.newContext(new Browser.NewContextOptions()
      .withViewport(400, 400)
      .withIsMobile(true));
    Page page = context.newPage();
    page.navigate(server.PREFIX + "/input/button.html");
    page.evalOnSelector("button", "button => {\n" +
      "  button.style.borderWidth = '8px';\n" +
      "  document.body.style.margin = '0';\n" +
      "}");
    page.click("button", new Page.ClickOptions().setPosition().withX(20).withY(10).done());
    assertEquals("Clicked", page.evaluate("result"));
    // 20;10 + 8px of border in each direction
    int expectedX = 28;
    int expectedY = 18;
    if (isWebKit) {
      // WebKit rounds up during css -> dip -> css conversion.
      expectedX = 29;
      expectedY = 19;
    } else if (isChromium && !headful) {
      // Headless Chromium rounds down during css -> dip -> css conversion.
      expectedX = 27;
      expectedY = 18;
    }
    assertEquals(expectedX, Math.round((Integer) page.evaluate("pageX") + 0.01));
    assertEquals(expectedY, Math.round((Integer) page.evaluate("pageY") + 0.01));
    context.close();
  }

  @Test
  void shouldWaitForStablePosition() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.evalOnSelector("button", "button => {\n" +
      "  button.style.transition = 'margin 500ms linear 0s';\n" +
      "  button.style.marginLeft = '200px';\n" +
      "  button.style.borderWidth = '0';\n" +
      "  button.style.width = '200px';\n" +
      "  button.style.height = '20px';\n" +
      "  // Set display to 'block' - otherwise Firefox layouts with non-even\n" +
      "  // values on Linux.\n" +
      "  button.style.display = 'block';\n" +
      "  document.body.style.margin = '0';\n" +
      "}");
    page.click("button");
    assertEquals("Clicked", page.evaluate("result"));
    assertEquals(300, page.evaluate("pageX"));
    assertEquals(10, page.evaluate("pageY"));
  }

  // TODO: not supported in sync api
  void shouldWaitForBecomingHitTarget() {
  }

  // TODO: support element handle
  void shouldFailWhenObscuredAndNotWaitingForHitTarget() {
  }

  // TODO: not supported in sync api
  void shouldWaitForButtonToBeEnabled() {
  }

  void shouldWaitForInputToBeEnabled() {
  }

  void shouldWaitForSelectToBeEnabled() {
  }

  @Test
  void shouldClickDisabledDiv() {
    page.setContent("<div onclick='javascript:window.__CLICKED=true;' disabled>Click target</div>");
    page.click("text=Click target");
    assertEquals(true, page.evaluate("__CLICKED"));
  }

  @Test
  void shouldClimbDomForInnerLabelWithPointerEventsNone() {
    page.setContent("<button onclick='javascript:window.__CLICKED=true;'><label style='pointer-events:none'>Click target</label></button>");
    page.click("text=Click target");
    assertEquals(true, page.evaluate("__CLICKED"));
  }

  @Test
  void shouldClimbUpTo_roleButton_() {
    page.setContent("<div role=button onclick='javascript:window.__CLICKED=true;'><div style='pointer-events:none'><span><div>Click target</div></span></div>");
    page.click("text=Click target");
    assertEquals(true, page.evaluate("__CLICKED"));
  }

  // TODO: not supported in sync api
  void shouldWaitForBUTTONToBeClickableWhenItHasPointerEventsNone() {
  }

  void shouldWaitForLABELToBeClickableWhenItHasPointerEventsNone() {
  }

  @Test
  void shouldUpdateModifiersCorrectly() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.click("button", new Page.ClickOptions().withModifiers(SHIFT));
    assertEquals(true, page.evaluate("shiftKey"));
    page.click("button", new Page.ClickOptions().withModifiers());
    assertEquals(false, page.evaluate("shiftKey"));

    page.keyboard().down("Shift");
    page.click("button", new Page.ClickOptions().withModifiers());
    assertEquals(false, page.evaluate("shiftKey"));
    page.click("button");
    assertEquals(true, page.evaluate("shiftKey"));
    page.keyboard().up("Shift");
    page.click("button");
    assertEquals(false, page.evaluate("shiftKey"));
  }

  @Test
  void shouldClickAnOffscreenElementWhenScrollBehaviorIsSmooth() {
    page.setContent(
      "<div style='border: 1px solid black; height: 500px; overflow: auto; width: 500px; scroll-behavior: smooth'>\n" +
        "    <button style='margin-top: 2000px' onClick='window.clicked = true'>hi</button>\n" +
        "    </div>");
    page.click("button");
    assertEquals(true, page.evaluate("window.clicked"));
  }

  // TODO: support element handle
  void shouldReportNiceErrorWhenElementIsDetachedAndForceClicked() {
  }

  // TODO: not supported in sync api
  void shouldFailWhenElementDetachesAfterAnimation() {
  }
  void shouldRetryWhenElementDetachesAfterAnimation() {
  }
  void shouldRetryWhenElementIsAnimatingFromOutsideTheViewport() {
  }
  void shouldFailWhenElementIsAnimatingFromOutsideTheViewportWithForce() {
  }
  @Test
  void shouldDispatchMicrotasksInOrder() {
    page.setContent(
      "<button id=button>Click me</button>\n" +
      "<script>\n" +
      "  let mutationCount = 0;\n" +
      "  const observer = new MutationObserver((mutationsList, observer) => {\n" +
      "    for(let mutation of mutationsList)\n" +
      "    ++mutationCount;\n" +
      "  });\n" +
      "  observer.observe(document.body, { attributes: true, childList: true, subtree: true });\n" +
      "  button.addEventListener('mousedown', () => {\n" +
      "    mutationCount = 0;\n" +
      "    document.body.appendChild(document.createElement('div'));\n" +
      "  });\n" +
      "  button.addEventListener('mouseup', () => {\n" +
      "    window['result'] = mutationCount;\n" +
      "  });\n" +
      "</script>");
    page.click("button");
    assertEquals(1, page.evaluate("() => window['result']"));
  }

  @Test
  void shouldClickTheButtonWhenWindowInnerWidthIsCorrupted() {
    page.navigate(server.PREFIX + "/input/button.html");
    page.evaluate("() => Object.defineProperty(window, 'innerWidth', {value: 0})");
    page.click("button");
    assertEquals("Clicked", page.evaluate("result"));
  }
}
