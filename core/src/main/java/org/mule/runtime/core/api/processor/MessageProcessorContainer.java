/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.api.processor;

/**
 * Identifies Constructs that contain Message Processors configured by the user. TODO MULE-10708 Replace
 * MessageProcessorContainer/MessageProcessorElementPath mechanism for paths with parser provided path information.
 */
public interface MessageProcessorContainer {

  /**
   * Add the child nodes to the path element tree.
   *
   * @param pathElement
   */
  void addMessageProcessorPathElements(MessageProcessorPathElement pathElement);
}
