/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extensions.jms.test;

import org.junit.Test;

public class DemoTestCase extends JmsAbstractTestCase {

  @Override
  protected String getConfigFile() {
    return "jms-demo.xml";
  }

  @Test
  public void demo() {
    int index = 0;
    while (index < 50) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      index++;
    }
  }

}
