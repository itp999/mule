/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.operation;

import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.junit.rules.ExpectedException.none;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mule.metadata.api.model.MetadataFormat.JAVA;
import static org.mule.runtime.api.message.NullAttributes.NULL_ATTRIBUTES;
import static org.mule.runtime.api.meta.model.ExecutionType.BLOCKING;
import static org.mule.runtime.api.meta.model.ExecutionType.CPU_INTENSIVE;
import static org.mule.runtime.api.meta.model.ExecutionType.CPU_LITE;
import static org.mule.runtime.api.metadata.MediaType.ANY;
import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_EXPRESSION_LANGUAGE;
import static org.mule.runtime.core.el.mvel.MessageVariableResolverFactory.FLOW_VARS;
import static org.mule.runtime.core.util.SystemUtils.getDefaultEncoding;
import static org.mule.runtime.extension.api.runtime.operation.Result.builder;
import static org.mule.runtime.module.extension.internal.metadata.PartAwareMetadataKeyBuilder.newKey;
import static org.mule.runtime.module.extension.internal.runtime.operation.OperationMessageProcessor.INVALID_TARGET_MESSAGE;
import static org.mule.tck.junit4.matcher.MetadataKeyMatcher.metadataKeyWithId;
import static org.mule.test.metadata.extension.resolver.TestNoConfigMetadataResolver.KeyIds.BOOLEAN;
import static org.mule.test.metadata.extension.resolver.TestNoConfigMetadataResolver.KeyIds.STRING;
import static org.mule.test.module.extension.internal.util.ExtensionsTestUtils.TYPE_BUILDER;
import static org.mule.test.module.extension.internal.util.ExtensionsTestUtils.toMetadataType;
import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.just;
import org.mule.metadata.api.annotation.DescriptionAnnotation;
import org.mule.metadata.api.builder.BaseTypeBuilder;
import org.mule.metadata.api.model.ObjectType;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.message.Attributes;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.ExecutionType;
import org.mule.runtime.api.meta.model.operation.OperationModel;
import org.mule.runtime.api.metadata.MediaType;
import org.mule.runtime.api.metadata.MetadataKey;
import org.mule.runtime.api.metadata.MetadataKeysContainer;
import org.mule.runtime.api.metadata.MetadataResolvingException;
import org.mule.runtime.api.metadata.descriptor.ComponentMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.OutputMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.TypeMetadataDescriptor;
import org.mule.runtime.api.metadata.resolving.MetadataResult;
import org.mule.runtime.api.metadata.resolving.OutputTypeResolver;
import org.mule.runtime.core.api.Event;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.el.ExtendedExpressionManager;
import org.mule.runtime.core.api.message.InternalMessage;
import org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType;
import org.mule.runtime.core.el.DefaultExpressionManager;
import org.mule.runtime.core.el.mvel.MVELExpressionLanguage;
import org.mule.runtime.core.policy.OperationExecutionFunction;
import org.mule.runtime.api.dsl.config.ComponentIdentifier;
import org.mule.runtime.extension.api.model.ImmutableOutputModel;
import org.mule.runtime.extension.api.runtime.operation.ExecutionContext;
import org.mule.runtime.module.extension.internal.runtime.ExecutionContextAdapter;
import org.mule.runtime.module.extension.internal.runtime.ValueResolvingException;
import org.mule.runtime.module.extension.internal.runtime.resolver.ValueResolver;
import org.mule.tck.size.SmallTest;

import java.util.Map;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.runners.MockitoJUnitRunner;

@SmallTest
@RunWith(MockitoJUnitRunner.class)
public class OperationMessageProcessorTestCase extends AbstractOperationMessageProcessorTestCase {

  private static final String SOME_PARAM_NAME = "someParam";

  @Rule
  public ExpectedException expectedException = none();

  @Override
  protected OperationMessageProcessor createOperationMessageProcessor() {
    return new OperationMessageProcessor(extensionModel, operationModel, configurationProvider, target, resolverSet,
                                         extensionManager, mockPolicyManager);
  }

  @Test
  public void operationContextIsWellFormed() throws Exception {
    ArgumentCaptor<ExecutionContext> operationContextCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
    messageProcessor.process(event);

    verify(operationExecutor).execute(operationContextCaptor.capture());
    ExecutionContext<ComponentModel> executionContext = operationContextCaptor.getValue();

    assertThat(executionContext, is(instanceOf(ExecutionContextAdapter.class)));
    ExecutionContextAdapter<ComponentModel> executionContextAdapter = (ExecutionContextAdapter) executionContext;

    assertThat(executionContextAdapter.getEvent(), is(sameInstance(event)));
    assertThat(executionContextAdapter.getConfiguration().get().getValue(), is(sameInstance(configuration)));
  }

  @Test
  public void operationExecutorIsInvoked() throws Exception {
    messageProcessor.process(event);
    verify(operationExecutor).execute(any(ExecutionContext.class));
  }

  @Test
  public void operationReturnsOperationResultWhichKeepsNoValues() throws Exception {
    Object payload = new Object();
    MediaType mediaType = ANY.withCharset(getDefaultEncoding(context));
    Attributes attributes = mock(Attributes.class);

    when(operationExecutor.execute(any(ExecutionContext.class)))
        .thenReturn(just(builder().output(payload).mediaType(mediaType).attributes(attributes).build()));

    InternalMessage message = messageProcessor.process(event).getMessage();
    assertThat(message, is(notNullValue()));

    assertThat(message.getPayload().getValue(), is(sameInstance(payload)));
    assertThat(message.getAttributes(), is(sameInstance(attributes)));
    assertThat(message.getPayload().getDataType().getMediaType(), is(mediaType));
  }

  @Test
  public void operationReturnsOperationResultOnTarget() throws Exception {
    target = TARGET_VAR;
    messageProcessor = setUpOperationMessageProcessor();

    Object payload = new Object();
    MediaType mediaType = ANY.withCharset(getDefaultEncoding(context));
    Attributes attributes = mock(Attributes.class);

    when(operationExecutor.execute(any(ExecutionContext.class)))
        .thenReturn(just(builder().output(payload).mediaType(mediaType).attributes(attributes).build()));

    InternalMessage message = (InternalMessage) messageProcessor.process(event).getVariable(TARGET_VAR).getValue();
    assertThat(message, is(notNullValue()));

    assertThat(message.getPayload().getValue(), is(sameInstance(payload)));
    assertThat(message.getAttributes(), is(sameInstance(attributes)));
    assertThat(message.getPayload().getDataType().getMediaType(), equalTo(mediaType));
  }

  @Test
  public void operationReturnsOperationResultButKeepsAttributes() throws Exception {
    Object payload = new Object();
    MediaType mediaType = ANY.withCharset(getDefaultEncoding(context));

    when(operationExecutor.execute(any(ExecutionContext.class)))
        .thenReturn(just(builder().output(payload).mediaType(mediaType).build()));

    event =
        Event.builder(event).message(InternalMessage.builder().payload("").attributes(mock(Attributes.class)).build()).build();

    InternalMessage message = messageProcessor.process(event).getMessage();
    assertThat(message, is(notNullValue()));

    assertThat(message.getPayload().getValue(), is(sameInstance(payload)));
    assertThat(message.getAttributes(), is(NULL_ATTRIBUTES));
    assertThat(message.getPayload().getDataType().getMediaType(), equalTo(mediaType));
  }

  @Test
  public void operationReturnsOperationResultThatOnlySpecifiesPayload() throws Exception {
    Object payload = "hello world!";

    when(operationExecutor.execute(any(ExecutionContext.class))).thenReturn(just(builder().output(payload).build()));
    event =
        Event.builder(event).message(InternalMessage.builder().payload("").attributes(mock(Attributes.class)).build()).build();

    InternalMessage message = messageProcessor.process(event).getMessage();
    assertThat(message, is(notNullValue()));

    assertThat(message.getPayload().getValue(), is(sameInstance(payload)));
    assertThat(message.getAttributes(), is(NULL_ATTRIBUTES));
    assertThat(message.getPayload().getDataType().getType().equals(String.class), is(true));
  }

  @Test
  public void operationReturnsOperationResultWithPayloadAndAttributes() throws Exception {
    Object payload = "hello world!";
    Attributes attributes = mock(Attributes.class);

    when(operationExecutor.execute(any(ExecutionContext.class)))
        .thenReturn(just(builder().output(payload).attributes(attributes).build()));

    InternalMessage message = messageProcessor.process(event).getMessage();
    assertThat(message, is(notNullValue()));

    assertThat(message.getPayload().getValue(), is(sameInstance(payload)));
    assertThat(message.getAttributes(), is(sameInstance(attributes)));
    assertThat(message.getPayload().getDataType().getType().equals(String.class), is(true));
  }

  @Test
  public void operationReturnsPayloadValue() throws Exception {
    Object value = new Object();
    when(operationExecutor.execute(any(ExecutionContext.class))).thenReturn(just(value));

    InternalMessage message = messageProcessor.process(event).getMessage();
    assertThat(message, is(notNullValue()));
    assertThat(message.getPayload().getValue(), is(sameInstance(value)));
  }

  @Test
  public void operationReturnsPayloadValueWithTarget() throws Exception {
    target = TARGET_VAR;
    messageProcessor = setUpOperationMessageProcessor();

    Object value = new Object();
    when(operationExecutor.execute(any(ExecutionContext.class))).thenReturn(just(value));

    InternalMessage message = (InternalMessage) messageProcessor.process(event).getVariable(TARGET_VAR).getValue();
    assertThat(message, is(notNullValue()));
    assertThat(message.getPayload().getValue(), is(sameInstance(value)));
  }

  @Test
  public void operationWithExpressionInTargetParameter() throws Exception {

    String flowName = "flowName";
    expectedException.expect(IllegalOperationException.class);
    expectedException.expectMessage(format(INVALID_TARGET_MESSAGE, flowName, operationModel.getName(), "an expression"));

    target = "#[mel:someExpression]";
    messageProcessor = createOperationMessageProcessor();

    when(context.getRegistry().lookupObject(OBJECT_EXPRESSION_LANGUAGE)).thenReturn(new MVELExpressionLanguage(context));
    doReturn(new DefaultExpressionManager(context)).when(context).getExpressionManager();
    FlowConstruct flowConstruct = mock(FlowConstruct.class);
    when(flowConstruct.getName()).thenReturn(flowName);

    messageProcessor.setFlowConstruct(flowConstruct);
    messageProcessor.setMuleContext(context);
    messageProcessor.initialise();
  }

  @Test
  public void operationWithFlowvarsPrefixInTargetParameter() throws Exception {

    String flowName = "flowName";
    expectedException.expect(IllegalOperationException.class);
    expectedException
        .expectMessage(format(INVALID_TARGET_MESSAGE, flowName, operationModel.getName(), format("the '%s' prefix", FLOW_VARS)));

    target = format("flowVars.%s", TARGET_VAR);
    messageProcessor = createOperationMessageProcessor();

    when(context.getExpressionManager()).thenReturn(mock(ExtendedExpressionManager.class));
    FlowConstruct flowConstruct = mock(FlowConstruct.class);
    when(flowConstruct.getName()).thenReturn(flowName);

    messageProcessor.setFlowConstruct(flowConstruct);
    messageProcessor.setMuleContext(context);
    messageProcessor.initialise();
  }

  @Test
  public void operationIsVoid() throws Exception {
    when(operationModel.getOutput())
        .thenReturn(new ImmutableOutputModel("Message.Payload", toMetadataType(void.class), false, emptySet()));
    messageProcessor = setUpOperationMessageProcessor();

    when(operationExecutor.execute(any(ExecutionContext.class))).thenReturn(empty());
    assertThat(messageProcessor.process(event), is(sameInstance(event)));
  }

  @Test
  public void executesWithDefaultConfig() throws Exception {
    configurationName = null;
    messageProcessor = setUpOperationMessageProcessor();

    Object defaultConfigInstance = new Object();
    when(configurationInstance.getValue()).thenReturn(defaultConfigInstance);
    when(extensionManager.getConfiguration(extensionModel, event)).thenReturn(configurationInstance);

    ArgumentCaptor<ExecutionContext> operationContextCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
    messageProcessor.process(event);
    verify(operationExecutor).execute(operationContextCaptor.capture());

    ExecutionContext<OperationModel> executionContext = operationContextCaptor.getValue();

    assertThat(executionContext, is(instanceOf(ExecutionContextAdapter.class)));
    assertThat(executionContext.getConfiguration().get().getValue(), is(sameInstance(defaultConfigInstance)));
  }

  @Test
  public void executeWithPolicy() throws Exception {
    ComponentIdentifier componentIdentifier =
        new ComponentIdentifier.Builder().withNamespace(EXTENSION_NAMESPACE).withName(OPERATION_NAME).build();
    when(extensionModel.getName()).thenReturn(componentIdentifier.getNamespace());
    when(operationModel.getName()).thenReturn(componentIdentifier.getName());

    messageProcessor.process(event);

    verify(mockPolicyManager).createOperationPolicy(eq(componentIdentifier), same(event), any(Map.class),
                                                    any(OperationExecutionFunction.class));
    verify(mockOperationPolicy).process(same(event));
  }

  @Test
  public void getExplicitOperationDynamicMetadata() throws Exception {
    MetadataResult<ComponentMetadataDescriptor> metadata = messageProcessor.getMetadata(newKey("person", "Person").build());

    assertThat(metadata.isSuccess(), is(true));

    OutputMetadataDescriptor outputMetadataDescriptor = metadata.get().getOutputMetadata();

    TypeMetadataDescriptor payloadMetadata = outputMetadataDescriptor.getPayloadMetadata();
    assertThat(payloadMetadata.getType(), is(TYPE_BUILDER.booleanType().build()));

    TypeMetadataDescriptor attributesMetadata = outputMetadataDescriptor.getAttributesMetadata();
    assertThat(attributesMetadata.getType(), is(TYPE_BUILDER.booleanType().build()));

    assertThat(metadata.get().getInputMetadata().getParameterMetadata("content").getType(),
               is(TYPE_BUILDER.stringType().build()));
    assertThat(metadata.get().getInputMetadata().getParameterMetadata("type").getType(), is(stringType));
  }

  @Test
  public void getDSLOperationDynamicMetadata() throws Exception {
    final ObjectType objectType = BaseTypeBuilder
        .create(JAVA).objectType()
        .with(new DescriptionAnnotation("Some Description"))
        .build();
    setUpValueResolvers();
    final OutputTypeResolver outputTypeResolver = mock(OutputTypeResolver.class);
    when(outputTypeResolver.getOutputType(any(), eq("person"))).thenReturn(objectType);
    when(metadataResolverFactory.getOutputResolver()).thenReturn(outputTypeResolver);

    final MetadataResult<ComponentMetadataDescriptor> metadata = messageProcessor.getMetadata();
    assertThat(metadata.isSuccess(), is(true));
    OutputMetadataDescriptor outputMetadata = metadata.get().getOutputMetadata();
    assertThat(outputMetadata.getPayloadMetadata().getType(), is(objectType));

    verify(resolverSet.getResolvers(), times(1));
  }

  @Test
  public void getMetadataKeyIdObjectValue() throws MetadataResolvingException, MuleException, ValueResolvingException {
    setUpValueResolvers();
    final Object metadataKeyValue = messageProcessor.getParameterValueResolver().getParameterValue(SOME_PARAM_NAME);
    assertThat(metadataKeyValue, is("person"));
  }

  @Test
  public void getMetadataKeys() throws Exception {
    MetadataResult<MetadataKeysContainer> metadataKeysResult = messageProcessor.getMetadataKeys();

    verify(metadataResolverFactory).getKeyResolver();

    assertThat(metadataKeysResult.isSuccess(), is(true));
    final Set<MetadataKey> metadataKeys = getKeysFromContainer(metadataKeysResult.get());
    assertThat(metadataKeys.size(), is(2));

    assertThat(metadataKeys, hasItem(metadataKeyWithId(BOOLEAN.name())));
    assertThat(metadataKeys, hasItem(metadataKeyWithId(STRING.name())));
  }

  @Test
  public void getProcessingType() {
    assertProcessingType(CPU_INTENSIVE, ProcessingType.CPU_INTENSIVE);
    assertProcessingType(CPU_LITE, ProcessingType.CPU_LITE);
    assertProcessingType(BLOCKING, ProcessingType.BLOCKING);
  }

  private void assertProcessingType(ExecutionType executionType, ProcessingType expectedProcessingType) {
    when(operationModel.getExecutionType()).thenReturn(executionType);
    assertThat(messageProcessor.getProcessingType(), is(expectedProcessingType));
  }


  private Set<MetadataKey> getKeysFromContainer(MetadataKeysContainer metadataKeysContainer) {
    return metadataKeysContainer.getKeys(metadataKeysContainer.getCategories().iterator().next()).get();
  }

  private void setUpValueResolvers() throws MuleException {
    final Map<String, ValueResolver> valueResolvers = mock(Map.class);
    when(resolverSet.getResolvers()).thenReturn(valueResolvers);
    final ValueResolver valueResolver = mock(ValueResolver.class);
    when(valueResolvers.get(eq(SOME_PARAM_NAME))).thenReturn(valueResolver);
    when(valueResolvers.containsKey(eq(SOME_PARAM_NAME))).thenReturn(true);
    when(valueResolver.resolve(any(Event.class))).thenReturn("person");
  }
}
