/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.module.http.functional.requester;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.mule.extension.http.api.HttpConstants.Methods.GET;
import static org.mule.extension.http.api.HttpConstants.Methods.POST;
import static org.mule.extension.http.api.HttpHeaders.Names.HOST;
import static org.mule.extension.http.api.HttpHeaders.Names.TRANSFER_ENCODING;
import static org.mule.functional.junit4.matchers.MessageMatchers.hasPayload;
import static org.mule.test.module.http.functional.HttpConnectorAllureConstants.HTTP_CONNECTOR_FEATURE;
import org.mule.runtime.core.api.Event;

import org.junit.Test;
import ru.yandex.qatools.allure.annotations.Features;

@Features(HTTP_CONNECTOR_FEATURE)
public class HttpRequestDynamicConfigTestCase extends AbstractHttpRequestTestCase {

  @Override
  protected String getConfigFile() {
    return "http-request-dynamic-configs.xml";
  }

  @Test
  public void requestsGoThroughClient1() throws Exception {
    Event result = flowRunner("client1")
        .withVariable("basePath", "api/v1")
        .withVariable("follow", true)
        .withVariable("send", "AUTO")
        .withVariable("host", "localhost")
        .withVariable("path", "clients")
        .withVariable("method", GET)
        .withPayload(TEST_MESSAGE)
        .run();
    assertThat(result.getMessage(), hasPayload(is(DEFAULT_RESPONSE)));
    assertThat(method, is(GET.toString()));
    assertThat(uri, is("/api/v1/clients"));
    assertThat(body, is(""));
    assertThat(headers.keys(), hasItem(HOST));
    assertThat(headers.get(HOST), hasItem(containsString("localhost:")));

    result = flowRunner("client1")
        .withVariable("basePath", "api/v2")
        .withVariable("follow", true)
        .withVariable("send", "ALWAYS")
        .withVariable("host", "localhost")
        .withVariable("path", "items")
        .withVariable("method", GET)
        .withPayload(TEST_MESSAGE)
        .run();
    assertThat(result.getMessage(), hasPayload(is(DEFAULT_RESPONSE)));
    assertThat(method, is(GET.toString()));
    assertThat(uri, is("/api/v2/items"));
    assertThat(body, is(TEST_MESSAGE));
    assertThat(headers.keys(), hasItem(HOST));
    assertThat(headers.get(HOST), hasItem(containsString("localhost:")));
  }

  @Test
  public void requestsGoThroughClient2() throws Exception {
    Event result = flowRunner("client2")
        .withVariable("parse", false)
        .withVariable("stream", "AUTO")
        .withVariable("timeout", 20000)
        .withVariable("port", httpPort.getNumber())
        .withVariable("body", TEST_PAYLOAD)
        .withPayload(TEST_MESSAGE)
        .run();
    assertThat(result.getMessage(), hasPayload(is(DEFAULT_RESPONSE)));
    assertThat(method, is(POST.toString()));
    assertThat(uri, is("/testPath"));
    assertThat(body, is(TEST_PAYLOAD));
    assertThat(headers.keys(), not(hasItem(TRANSFER_ENCODING)));

    result = flowRunner("client2")
        .withVariable("parse", false)
        .withVariable("stream", "ALWAYS")
        .withVariable("timeout", 20000)
        .withVariable("port", httpPort.getNumber())
        .withVariable("body", TEST_PAYLOAD)
        .withPayload(TEST_MESSAGE)
        .run();
    assertThat(result.getMessage(), hasPayload(is(DEFAULT_RESPONSE)));
    assertThat(method, is(POST.toString()));
    assertThat(uri, is("/testPath"));
    assertThat(body, is(TEST_PAYLOAD));
    assertThat(headers.keys(), hasItem(TRANSFER_ENCODING));
  }

}
