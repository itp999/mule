/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.test.integration.interception;

import static org.mule.functional.functional.FlowAssert.verify;
import static org.mule.runtime.api.dsl.DslConstants.CORE_NAMESPACE;
import org.mule.runtime.api.dsl.config.ComponentIdentifier;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.ConfigurationBuilder;
import org.mule.runtime.core.api.config.ConfigurationException;
import org.mule.runtime.core.api.interception.MessageProcessorInterceptorCallback;
import org.mule.runtime.core.exception.MessagingException;
import org.mule.test.AbstractIntegrationTestCase;

import java.util.List;
import java.util.Map;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MessageProcessorInterceptionFlowTestCase extends AbstractIntegrationTestCase {

  public static final String INTERCEPTED = "intercepted";
  public static final String EXPECTED_INTERCEPTED_MESSAGE = TEST_MESSAGE + " " + INTERCEPTED;

  private ComponentIdentifier loggerComponentIdentifier =
      new ComponentIdentifier.Builder().withNamespace(CORE_NAMESPACE).withName("logger").build();
  private ComponentIdentifier fileReadComponentIdentifier =
      new ComponentIdentifier.Builder().withNamespace("file").withName("read").build();
  private ComponentIdentifier customInterceptorComponentIdentifier =
      new ComponentIdentifier.Builder().withNamespace(CORE_NAMESPACE).withName("custom-interceptor").build();

  private ProcessorInterceptorCallbackHolder loggerCallbackHolder;
  private ProcessorInterceptorCallbackHolder fileReadCallbackHolder;
  private ProcessorInterceptorCallbackHolder customInterceptorCallbackHolder;

  @ClassRule
  public static TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Override
  protected String getConfigFile() {
    return "org/mule/test/integration/interception/interception-flow.xml";
  }

  @Override
  protected void addBuilders(List<ConfigurationBuilder> builders) {
    builders.add(new ConfigurationBuilder() {

      @Override
      public void configure(MuleContext muleContext) throws ConfigurationException {
        loggerCallbackHolder = new ProcessorInterceptorCallbackHolder();
        fileReadCallbackHolder = new ProcessorInterceptorCallbackHolder();
        customInterceptorCallbackHolder = new ProcessorInterceptorCallbackHolder();

        //TODO interception should not be registered by componentIdentifier, it should be always intercepted
        muleContext.getMessageProcessorInterceptorManager().registerInterceptionCallback(loggerComponentIdentifier,
                                                                                         loggerCallbackHolder);
        muleContext.getMessageProcessorInterceptorManager().registerInterceptionCallback(fileReadComponentIdentifier,
                                                                                         fileReadCallbackHolder);
        muleContext.getMessageProcessorInterceptorManager().registerInterceptionCallback(customInterceptorComponentIdentifier,
                                                                                         customInterceptorCallbackHolder);
      }

      @Override
      public boolean isConfigured() {
        return true;
      }
    });
    super.addBuilders(builders);
  }

  @Test
  public void interceptLoggerMessageProcessor() throws Exception {
    loggerCallbackHolder.setDelegate(new DoProcessorInterceptorCallback());
    String flow = "loggerProcessorFlow";
    flowRunner(flow).withVariable("expectedMessage", EXPECTED_INTERCEPTED_MESSAGE).withPayload(TEST_MESSAGE).run().getMessage();
    verify(flow);
  }

  @Test
  public void interceptOperationMessageProcessor() throws Exception {
    fileReadCallbackHolder.setDelegate(new DoProcessorInterceptorCallback());
    String flow = "operationProcessorFlow";
    flowRunner(flow).withVariable("expectedMessage", EXPECTED_INTERCEPTED_MESSAGE)
        .withVariable("source", temporaryFolder.getRoot()).withPayload(TEST_MESSAGE).run().getMessage();
    verify(flow);
  }

  @Test
  public void interceptCustomInterceptorMessageProcessor() throws Exception {
    customInterceptorCallbackHolder.setDelegate(new DoNotProcessorInterceptorCallback());
    String flow = "customInterceptorProcessorFlow";
    flowRunner(flow).withVariable("expectedMessage", TEST_MESSAGE + "!").withPayload(TEST_MESSAGE).run().getMessage();
    verify(flow);
  }

  @Test
  public void interceptCustomInterceptorMessageProcessorNotInvoked() throws Exception {
    customInterceptorCallbackHolder.setDelegate(new DoProcessorInterceptorCallback());
    String flow = "customInterceptorNotInvokedProcessorFlow";
    flowRunner(flow).withVariable("expectedMessage", EXPECTED_INTERCEPTED_MESSAGE).withPayload(TEST_MESSAGE).run().getMessage();
    verify(flow);
  }

  class ProcessorInterceptorCallbackHolder implements MessageProcessorInterceptorCallback {

    private MessageProcessorInterceptorCallback delegate;

    public void setDelegate(MessageProcessorInterceptorCallback delegate) {
      this.delegate = delegate;
    }

    @Override
    public Message before(Message message, Map<String, String> parameters) throws MuleException {
      return delegate.before(message, parameters);
    }

    @Override
    public boolean shouldExecuteProcessor(Message message, Map<String, String> parameters) {
      return delegate.shouldExecuteProcessor(message, parameters);
    }

    @Override
    public Message getResult(Message message, Map<String, String> parameters) throws MuleException {
      return delegate.getResult(message, parameters);
    }

    @Override
    public void after(Message resultMessage, Map<String, String> parameters, MessagingException e) {
      delegate.after(resultMessage, parameters, e);
    }
  }

  class DoProcessorInterceptorCallback implements MessageProcessorInterceptorCallback {

    @Override
    public boolean shouldExecuteProcessor(Message message, Map<String, String> parameters) {
      return false;
    }

    @Override
    public Message getResult(Message message, Map<String, String> parameters) throws MuleException {
      final Message response = Message.builder()
          .payload(message.getPayload().getValue() + " " + INTERCEPTED)
          .build();
      //System.out.println("INTERCEPTING --- request: '" + message.getPayload().getValue() + "' response: '"
      //    + response.getPayload().getValue() + "'");
      return response;
    }

  }

  class DoNotProcessorInterceptorCallback implements MessageProcessorInterceptorCallback {

    @Override
    public Message getResult(Message message, Map<String, String> parameters) throws MuleException {
      throw new IllegalStateException("Should not be invoked as the intercepted processor should be called");
    }
  }

}
