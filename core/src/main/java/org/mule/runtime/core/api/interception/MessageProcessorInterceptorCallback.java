/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.runtime.core.api.interception;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.core.exception.MessagingException;

import java.util.Map;

/**
 * TODO
 */
public interface MessageProcessorInterceptorCallback {

  default Message before(Message message, Map<String, String> parameters) throws MuleException {
    return message;
  }

  default boolean shouldExecuteProcessor(Message message, Map<String, String> parameters) {
    return true;
  }

  Message getResult(Message message, Map<String, String> parameters) throws MuleException;

  //TODO should support throwing an exception
  //TODO should all also support changing the Message (debugger use case)
  default void after(Message resultMessage, Map<String, String> parameters, MessagingException e) {}

}
