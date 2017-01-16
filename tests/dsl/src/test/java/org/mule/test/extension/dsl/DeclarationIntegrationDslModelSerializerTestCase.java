/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.extension.dsl;

import static org.mule.runtime.api.app.declaration.fluent.ElementConfigurationDeclarer.newApp;
import static org.mule.runtime.api.app.declaration.fluent.ElementConfigurationDeclarer.newFlow;
import static org.mule.runtime.api.app.declaration.fluent.ElementConfigurationDeclarer.newListValue;
import static org.mule.runtime.api.app.declaration.fluent.ElementConfigurationDeclarer.newObjectValue;
import static org.mule.runtime.api.dsl.DslConstants.REDELIVERY_POLICY_ELEMENT_IDENTIFIER;
import static org.mule.runtime.core.util.IOUtils.getResourceAsString;
import static org.mule.runtime.extension.api.ExtensionConstants.RECONNECTION_STRATEGY_PARAMETER_NAME;
import static org.mule.runtime.extension.api.ExtensionConstants.REDELIVERY_POLICY_PARAMETER_NAME;
import static org.mule.runtime.extension.api.declaration.type.ReconnectionStrategyTypeBuilder.COUNT;
import static org.mule.runtime.extension.api.declaration.type.ReconnectionStrategyTypeBuilder.FREQUENCY;
import static org.mule.runtime.extension.api.declaration.type.ReconnectionStrategyTypeBuilder.RECONNECT_ALIAS;
import static org.mule.runtime.extension.api.declaration.type.RedeliveryPolicyTypeBuilder.MAX_REDELIVERY_COUNT;
import static org.mule.runtime.extension.api.declaration.type.RedeliveryPolicyTypeBuilder.USE_SECURE_HASH;
import static org.mule.test.module.extension.internal.util.ExtensionsTestUtils.compareXML;
import org.mule.runtime.api.app.config.ComponentConfiguration;
import org.mule.runtime.api.app.declaration.ArtifactDeclaration;
import org.mule.runtime.api.app.declaration.fluent.ElementConfigurationDeclarer;
import org.mule.runtime.config.spring.dsl.model.XmlDslElementModelConverter;

import java.io.IOException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;

public class DeclarationIntegrationDslModelSerializerTestCase extends AbstractElementModelTestCase {

  private Element flow;
  private String expectedAppXml;
  private ArtifactDeclaration applicationDeclaration;

  protected void createAppDeclaration() {

    ElementConfigurationDeclarer db = ElementConfigurationDeclarer.forExtension("Database");
    ElementConfigurationDeclarer http = ElementConfigurationDeclarer.forExtension("HTTP");


    applicationDeclaration = newApp("sampleApp")
        .withConfig(db.newConfiguration("config")
            .withConnection(db.newConnection("derby-connection")
                .withParameter("database", "target/muleEmbeddedDB")
                .withParameter("create", "true")
                .getDeclaration())
            .getDeclaration())
        .withConfig(http.newConfiguration("listener-config")
            .withRefName("httpListener")
            .withParameter("basePath", "/")
            .withConnection(http.newConnection("listener-connection")
                .withParameter("host", "localhost")
                .withParameter("port", "49019")
                .getDeclaration())
            .getDeclaration())
        .withConfig(http.newConfiguration("request-config")
            .withRefName("httpRequester")
            .withConnection(http.newConnection("request-connection")
                .withParameter("host", "localhost")
                .withParameter("port", "49020")
                .withParameter("authentication",
                               newObjectValue()
                                   .ofType(
                                           "org.mule.extension.http.api.request.authentication.BasicAuthentication")
                                   .withParameter("username", "user")
                                   .withParameter("password", "pass")
                                   .build())
                .withParameter("clientSocketProperties",
                               newObjectValue()
                                   .withParameter("connectionTimeout", "1000")
                                   .withParameter("keepAlive", "true")
                                   .withParameter("receiveBufferSize", "1024")
                                   .withParameter("sendBufferSize", "1024")
                                   .withParameter("clientTimeout", "1000")
                                   .withParameter("linger", "1000")
                                   .withParameter("sendTcpNoDelay", "true")
                                   .build())
                .getDeclaration())
            .getDeclaration())
        .withFlow(newFlow("testFlow")
            .withInitialState("stopped")
            .withComponent(http.newOperation("listener")
                .withConfig("httpListener")
                .withParameter("path", "testBuilder")
                .withParameter(REDELIVERY_POLICY_PARAMETER_NAME,
                               newObjectValue()
                                   .withParameter(MAX_REDELIVERY_COUNT, "2")
                                   .withParameter(USE_SECURE_HASH, "true")
                                   .build())
                .withParameter(RECONNECTION_STRATEGY_PARAMETER_NAME,
                               newObjectValue()
                                   .ofType(RECONNECT_ALIAS) //FIXME hackish alias for infrastructure union type
                                   .withParameter(COUNT, "1")
                                   .withParameter(FREQUENCY, "0")
                                   .build())
                .withParameter("responseBuilder",
                               newObjectValue()
                                   .withParameter("headers", "#[['content-type' : 'text/plain']]")
                                   .build())
                .getDeclaration())
            .withComponent(db.newOperation("bulkInsert")
                .withParameter("sql", "INSERT INTO PLANET(POSITION, NAME) VALUES (:position, :name)")
                .withParameter("parameterTypes",
                               newListValue()
                                   .withValue(newObjectValue()
                                       .withParameter("key", "name")
                                       .withParameter("type", "VARCHAR").build())
                                   .withValue(newObjectValue()
                                       .withParameter("key", "position")
                                       .withParameter("type", "INTEGER").build())
                                   .build())
                .getDeclaration())
            .withComponent(http.newOperation("request")
                .withConfig("httpRequester")
                .withParameter("path", "/nested")
                .withParameter("method", "POST")
                .getDeclaration())
            .getDeclaration())
        .getDeclaration();
  }

  private void createAppDocument() throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    DocumentBuilder docBuilder = factory.newDocumentBuilder();

    this.doc = docBuilder.newDocument();
    Element mule = doc.createElement("mule");
    doc.appendChild(mule);
    mule.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns", "http://www.mulesoft.org/schema/mule/core");
    mule.setAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "xsi:schemaLocation",
                        "http://www.mulesoft.org/schema/mule/core http://www.mulesoft.org/schema/mule/core/current/mule.xsd");
  }

  @Before
  public void setupArtifact() throws Exception {
    createAppDeclaration();
    createAppDocument();
  }

  @Before
  public void loadExpectedResult() throws IOException {
    expectedAppXml = getResourceAsString(getConfigFile(), getClass());
  }

  @Test
  public void serialize() throws Exception {
    XmlDslElementModelConverter converter = XmlDslElementModelConverter.getDefault(this.doc);

    applicationDeclaration
        .getConfigs()
        .forEach(declaration -> doc.getDocumentElement()
            .appendChild(converter.asXml(modelResolver.create(declaration))));



    applicationDeclaration
        .getFlows()
        .forEach(flowDeclaration -> {
          Element flow = doc.createElement("flow");
          flow.setAttribute("name", flowDeclaration.getName());
          flow.setAttribute("initialState", flowDeclaration.getInitialState());

          flowDeclaration.getComponents()
              .forEach(component -> flow
                  .appendChild(converter.asXml(modelResolver.create(component))));

          doc.getDocumentElement().appendChild(flow);
        });
    //ComponentConfiguration componentsFlow = getAppElement(applicationModel, COMPONENTS_FLOW);
    //Element httpListenerSource = converter.asXml(resolve(componentsFlow.getNestedComponents().get(LISTENER_PATH)));
    //
    //// For some reason mule provides the `redelivery-policy` as an external component, but we need to serialize it
    //// as an http child element to match the original application
    //addRedeliveryPolicy(componentsFlow, httpListenerSource);
    //flow.appendChild(httpListenerSource);
    //
    //flow.appendChild(converter.asXml(resolve(componentsFlow.getNestedComponents().get(DB_INSERT_PATH))));
    //flow.appendChild(converter.asXml(resolve(componentsFlow.getNestedComponents().get(REQUESTER_PATH))));
    //
    //doc.getDocumentElement().appendChild(flow);

    // artifact declaration should be aware of its dependencies?
    muleContext.getExtensionManager().getExtensions().forEach(e -> addSchemaLocation(doc, e));


    String serializationResult = write();

    compareXML(expectedAppXml, serializationResult);
  }

  private void addRedeliveryPolicy(ComponentConfiguration componentsFlow, Element httpListenerSource) {
    ComponentConfiguration redeliveryPolicy = componentsFlow.getNestedComponents().get(1);
    Element policyElement = doc.createElement(REDELIVERY_POLICY_ELEMENT_IDENTIFIER);
    redeliveryPolicy.getParameters().forEach(policyElement::setAttribute);
    httpListenerSource.insertBefore(policyElement, httpListenerSource.getFirstChild());
  }

}
