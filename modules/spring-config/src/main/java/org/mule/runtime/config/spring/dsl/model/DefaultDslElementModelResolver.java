/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.spring.dsl.model;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Stream.concat;
import static org.mule.metadata.api.utils.MetadataTypeUtils.getLocalPart;
import static org.mule.runtime.api.dsl.DslConstants.VALUE_ATTRIBUTE_NAME;
import static org.mule.runtime.extension.internal.dsl.syntax.DslSyntaxUtils.getIdentifier;
import org.mule.metadata.api.model.ArrayType;
import org.mule.metadata.api.model.MetadataType;
import org.mule.metadata.api.model.ObjectFieldType;
import org.mule.metadata.api.model.ObjectType;
import org.mule.runtime.api.app.config.ComponentConfiguration;
import org.mule.runtime.api.app.config.ComponentIdentifier;
import org.mule.runtime.api.app.declaration.ComponentElementDeclaration;
import org.mule.runtime.api.app.declaration.ConfigurationElementDeclaration;
import org.mule.runtime.api.app.declaration.ConnectionElementDeclaration;
import org.mule.runtime.api.app.declaration.ParameterValue;
import org.mule.runtime.api.app.declaration.ParameterValueVisitor;
import org.mule.runtime.api.app.declaration.ParameterizedElementDeclaration;
import org.mule.runtime.api.app.declaration.fluent.ParameterListValue;
import org.mule.runtime.api.app.declaration.fluent.ParameterObjectValue;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.config.ConfigurationModel;
import org.mule.runtime.api.meta.model.connection.ConnectionProviderModel;
import org.mule.runtime.api.meta.model.operation.HasOperationModels;
import org.mule.runtime.api.meta.model.operation.OperationModel;
import org.mule.runtime.api.meta.model.parameter.ParameterGroupModel;
import org.mule.runtime.api.meta.model.parameter.ParameterModel;
import org.mule.runtime.api.meta.model.parameter.ParameterizedModel;
import org.mule.runtime.api.meta.model.source.HasSourceModels;
import org.mule.runtime.api.meta.model.source.SourceModel;
import org.mule.runtime.api.meta.model.util.ExtensionWalker;
import org.mule.runtime.api.util.Reference;
import org.mule.runtime.extension.api.dsl.DslResolvingContext;
import org.mule.runtime.extension.api.dsl.syntax.DslElementSyntax;
import org.mule.runtime.extension.api.dsl.syntax.resolver.DslSyntaxResolver;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default implementation of a {@link DslElementModelResolver}
 *
 * @since 1.0
 */
public class DefaultDslElementModelResolver implements DslElementModelResolver {

  private final DslResolvingContext context;
  private Map<ExtensionModel, DslSyntaxResolver> resolvers = new HashMap<>();
  private ExtensionModel current;
  private DslSyntaxResolver dsl;

  public DefaultDslElementModelResolver(DslResolvingContext context) {
    this.context = context;

    context.getExtensions().forEach(extensionModel -> resolvers.put(extensionModel,
                                                                    DslSyntaxResolver.getDefault(extensionModel, context)));
  }

  @Override
  public <T extends org.mule.runtime.api.meta.model.ComponentModel> DslElementModel<T> resolve(
    ComponentElementDeclaration componentDeclaration) {
    this.current = context.getExtension(componentDeclaration.getDeclaringExtension())
      .orElseThrow(() -> new IllegalArgumentException());
    this.dsl = resolvers.get(current);

    Optional<? extends org.mule.runtime.api.meta.model.ComponentModel> model =
      current.getOperationModel(componentDeclaration.getName());

    if (!model.isPresent()) {
      model = current.getSourceModel(componentDeclaration.getName());
    }

    if (!model.isPresent()) {
      throw new IllegalArgumentException();
    }

    DslElementSyntax configDsl = dsl.resolve(model.get());

    ComponentConfiguration.Builder configuration = ComponentConfiguration.builder()
      .withIdentifier(asIdentifier(configDsl));

    DslElementModel.Builder<? extends org.mule.runtime.api.meta.model.ComponentModel> element =
      createParameterizedElementModel(model.get(), configDsl, componentDeclaration, configuration);

    return (DslElementModel<T>) element.withConfig(configuration.build()).build();
  }

  @Override
  public DslElementModel<ConfigurationModel> resolve(ConfigurationElementDeclaration configurationDeclaration) {
    this.current = context.getExtension(configurationDeclaration.getDeclaringExtension())
      .orElseThrow(() -> new IllegalArgumentException());
    this.dsl = resolvers.get(current);


    ConfigurationModel model = current.getConfigurationModel(configurationDeclaration.getName())
      .orElseThrow(() -> new IllegalArgumentException());

    DslElementSyntax configDsl = dsl.resolve(model);

    ComponentConfiguration.Builder configuration = ComponentConfiguration.builder()
      .withIdentifier(asIdentifier(configDsl));

    DslElementModel.Builder<ConfigurationModel> element =
      createParameterizedElementModel(model, configDsl, configurationDeclaration, configuration);


    configurationDeclaration.getConnection()
      .ifPresent(connection -> addConnectionProvider(connection, model, configuration, element));

    return element.withConfig(configuration.build()).build();
  }

  private void addConnectionProvider(ConnectionElementDeclaration connection,
                                     ConfigurationModel model,
                                     ComponentConfiguration.Builder configuration,
                                     DslElementModel.Builder<ConfigurationModel> configElement) {

    concat(model.getConnectionProviders().stream(), current.getConnectionProviders()
      .stream())
      .filter(c -> c.getName().equals(connection.getName()))
      .findFirst()
      .ifPresent(provider -> {
        DslElementSyntax providerDsl = dsl.resolve(provider);

        ComponentConfiguration.Builder builder = ComponentConfiguration.builder()
          .withIdentifier(asIdentifier(providerDsl));

        DslElementModel.Builder<ConnectionProviderModel> element =
          createParameterizedElementModel(provider, providerDsl, connection, builder);

        ComponentConfiguration providerConfig = builder.build();

        configuration.withNestedComponent(providerConfig);
        configElement.containing(element.withConfig(providerConfig).build());
      });
  }


  private <T extends ParameterizedModel> DslElementModel.Builder<T> createParameterizedElementModel(T model,
                                                                                                    DslElementSyntax elementDsl,
                                                                                                    ParameterizedElementDeclaration declaration,
                                                                                                    ComponentConfiguration.Builder parentConfig) {
    DslElementModel.Builder<T> parameterizedElement = DslElementModel.<T>builder()
      .withModel(model)
      .withDsl(elementDsl);

    declaration.getParameters().forEach(parameter -> {

      ParameterModel parameterModel = model.getAllParameterModels().stream()
        .filter(p -> p.getName().equals(parameter.getName()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException());// TODO infra parameters

      DslElementSyntax paramDsl = elementDsl.getContainedElement(parameter.getName())
        .orElseThrow(() -> new IllegalArgumentException());// TODO infra parameters

      DslElementModel.Builder<ParameterModel> parameterElementBuilder = DslElementModel.<ParameterModel>builder()
        .withModel(parameterModel)
        .withDsl(paramDsl);

      parameter.getValue().accept(new ParameterValueVisitor() {

        @Override
        public void visitSimpleValue(String value) {
          if (paramDsl.supportsAttributeDeclaration()) {
            // attribute parameters imply no further nesting in the configs
            parentConfig.withParameter(paramDsl.getAttributeName(), value);

          } else {
            // we are in the content case, so we have one more nesting level
            ComponentConfiguration parameterConfig = ComponentConfiguration.builder()
              .withIdentifier(asIdentifier(paramDsl))
              .withValue(value)
              .build();

            parameterElementBuilder.withConfig(parameterConfig);
          }
        }

        @Override
        public void visitListValue(ParameterListValue list) {
          // the parameter is of list type, so we have nested elements
          // we'll resolve this based on the type of the parameter, since no
          // further model information is available
          ComponentConfiguration listComponent = createListConfiguration(list, paramDsl, (ArrayType) parameterModel.getType());
          parameterElementBuilder.withConfig(listComponent);
        }

        @Override
        public void visitObjectValue(ParameterObjectValue objectValue) {
          // the parameter is of complex object type, so we have both nested elements
          // and attributes as values of this element.
          // we'll resolve this based on the type of the parameter, since no
          // further model information is available
          ComponentConfiguration objectComponent =
            createObjectConfiguration(objectValue, paramDsl, (ObjectType) parameterModel.getType());
          parameterElementBuilder.withConfig(objectComponent);
        }
      });

      DslElementModel<ParameterModel> parameterElement = parameterElementBuilder.build();

      // we declare the elementModel in order to keep the model related to the config
      parameterizedElement.containing(parameterElement);

      // if we have a nested configuration, append it to the parent in order to keep
      // the node's nesting
      parameterElement.getConfiguration().ifPresent(parentConfig::withNestedComponent);
    });

    return parameterizedElement;
  }

  private ComponentConfiguration createListItemConfig(MetadataType valueType, ParameterValue value, DslElementSyntax itemDsl) {

    ComponentConfiguration.Builder listConfig = ComponentConfiguration.builder()
      .withIdentifier(asIdentifier(itemDsl));

    value.accept(new ParameterValueVisitor() {

      @Override
      public void visitSimpleValue(String value) {
        listConfig.withParameter(VALUE_ATTRIBUTE_NAME, value);
      }

      @Override
      public void visitListValue(ParameterListValue list) {
        MetadataType genericType = ((ArrayType) valueType).getType();
        DslElementSyntax genericDsl = itemDsl.getGeneric(genericType).orElseThrow(() -> new IllegalStateException());

        list.getValues().forEach(value -> listConfig.withNestedComponent(createListItemConfig(genericType, value, genericDsl)));
      }

      @Override
      public void visitObjectValue(ParameterObjectValue objectValue) {
        listConfig.withNestedComponent(createObjectConfiguration(objectValue, itemDsl, (ObjectType) valueType));
      }
    });

    return listConfig.build();
  }


  private void addObjectFieldConfiguration(MetadataType fieldType, ParameterValue fieldValue,
                                           DslElementSyntax fieldDsl,
                                           ComponentConfiguration.Builder objectConfig) {

    fieldValue.accept(new ParameterValueVisitor() {

      @Override
      public void visitSimpleValue(String value) {
        if (fieldDsl.supportsAttributeDeclaration()) {
          objectConfig.withParameter(fieldDsl.getAttributeName(), value);

        } else {
          objectConfig.withNestedComponent(ComponentConfiguration.builder()
                                             .withIdentifier(asIdentifier(fieldDsl))
                                             .withValue(value)
                                             .build());
        }
      }

      @Override
      public void visitListValue(ParameterListValue list) {
        objectConfig.withNestedComponent(createListConfiguration(list, fieldDsl, (ArrayType) fieldType));
      }

      @Override
      public void visitObjectValue(ParameterObjectValue objectValue) {
        objectConfig.withNestedComponent(createObjectConfiguration(objectValue, fieldDsl, (ObjectType) fieldType));
      }
    });
  }

  private ComponentConfiguration createListConfiguration(ParameterListValue list, DslElementSyntax fieldDsl,
                                                         ArrayType fieldType) {
    ComponentConfiguration.Builder fieldConfig = ComponentConfiguration.builder()
      .withIdentifier(asIdentifier(fieldDsl));

    MetadataType itemType = fieldType.getType();
    DslElementSyntax itemDsl = fieldDsl.getGeneric(itemType).orElseThrow(() -> new IllegalArgumentException());
    list.getValues()
      .forEach(value -> fieldConfig.withNestedComponent(createListItemConfig(itemType, value, itemDsl)));

    return fieldConfig.build();
  }

  private ComponentConfiguration createObjectConfiguration(ParameterObjectValue objectValue,
                                                           DslElementSyntax objectDsl,
                                                           ObjectType type) {
    ComponentConfiguration.Builder fieldConfig = ComponentConfiguration.builder()
      .withIdentifier(asIdentifier(objectDsl));

    Collection<ObjectFieldType> fields = type.getFields();

    objectValue.getParameters()
      .forEach((name, value) ->
                 fields.stream()
                   .filter(f -> getLocalPart(f).equals(name))
                   .findFirst()
                   .ifPresent(field -> objectDsl.getContainedElement(name)
                     .ifPresent(nestedDsl -> addObjectFieldConfiguration(field.getValue(), value, nestedDsl, fieldConfig)))
      );
    return fieldConfig.build();
  }

  private ComponentIdentifier asIdentifier(DslElementSyntax fieldDsl) {
    return getIdentifier(fieldDsl).orElseThrow(() -> new IllegalArgumentException());
  }


  /**
   * {@inheritDoc}
   */
  @Override
  public <T> Optional<DslElementModel<T>> resolve(ComponentConfiguration configuration) {
    return Optional.ofNullable(createIdentifiedElement(configuration));
  }

  private DslElementModel createIdentifiedElement(ComponentConfiguration configuration) {

    final ComponentIdentifier identifier = configuration.getIdentifier();

    Optional<Map.Entry<ExtensionModel, DslSyntaxResolver>> entry =
      resolvers.entrySet().stream()
        .filter(e -> e.getKey().getXmlDslModel().getNamespaceUri().equals(identifier.getNamespace()))
        .findFirst();

    if (!entry.isPresent()) {
      return null;
    }

    final ExtensionModel extension = entry.get().getKey();
    final DslSyntaxResolver dsl = entry.get().getValue();


    Reference<DslElementModel> elementModel = new Reference<>();
    new ExtensionWalker() {

      @Override
      protected void onConfiguration(ConfigurationModel model) {
        final DslElementSyntax elementDsl = dsl.resolve(model);
        getIdentifier(elementDsl).ifPresent(elementId -> {
          if (elementId.equals(identifier)) {
            DslElementModel.Builder<ConfigurationModel> element = createElementModel(model, elementDsl, configuration);
            addConnectionProvider(extension, model, dsl, element, configuration);
            elementModel.set(element.build());
            stop();
          }
        });

      }

      @Override
      protected void onOperation(HasOperationModels owner, OperationModel model) {
        final DslElementSyntax elementDsl = dsl.resolve(model);
        getIdentifier(elementDsl).ifPresent(elementId -> {
          if (elementId.equals(identifier)) {
            elementModel.set(createElementModel(model, elementDsl, configuration).build());
            stop();
          }
        });
      }

      @Override
      protected void onSource(HasSourceModels owner, SourceModel model) {
        final DslElementSyntax elementDsl = dsl.resolve(model);
        getIdentifier(elementDsl).ifPresent(elementId -> {
          if (elementId.equals(identifier)) {
            elementModel.set(createElementModel(model, elementDsl, configuration).build());
            stop();
          }
        });
      }

    }.walk(extension);

    if (elementModel.get() == null) {
      resolveBasedOnTypes(extension, dsl, configuration)
        .ifPresent(elementModel::set);
    }

    return elementModel.get();
  }

  private Optional<DslElementModel<ObjectType>> resolveBasedOnTypes(ExtensionModel extension, DslSyntaxResolver dsl,
                                                                    ComponentConfiguration configuration) {
    return extension.getTypes().stream()
      .map(type -> {
        Optional<DslElementSyntax> typeDsl = dsl.resolve(type);
        if (typeDsl.isPresent()) {
          Optional<ComponentIdentifier> elementIdentifier = getIdentifier(typeDsl.get());
          if (elementIdentifier.isPresent() && elementIdentifier.get().equals(configuration.getIdentifier())) {
            return DslElementModel.<ObjectType>builder()
              .withModel(type)
              .withDsl(typeDsl.get())
              .withConfig(configuration)
              .build();
          }
        }
        return null;
      }).filter(Objects::nonNull)
      .findFirst();
  }

  private DslElementModel.Builder<ConfigurationModel> addConnectionProvider(ExtensionModel extension,
                                                                            ConfigurationModel model,
                                                                            DslSyntaxResolver dsl,
                                                                            DslElementModel.Builder<ConfigurationModel> element,
                                                                            ComponentConfiguration configuration) {

    concat(model.getConnectionProviders().stream(), extension.getConnectionProviders()
      .stream())
      .map(provider -> {
        DslElementSyntax providerDsl = dsl.resolve(provider);
        ComponentIdentifier identifier = getIdentifier(providerDsl).orElse(null);
        return configuration.getNestedComponents().stream()
          .filter(c -> c.getIdentifier().equals(identifier))
          .findFirst()
          .map(providerConfig -> element.containing(createElementModel(provider, providerDsl, providerConfig).build()))
          .orElse(null);
      })
      .filter(Objects::nonNull)
      .findFirst();

    return element;
  }

  private <T extends ParameterizedModel> DslElementModel.Builder<T> createElementModel(T model, DslElementSyntax elementDsl,
                                                                                       ComponentConfiguration configuration) {
    DslElementModel.Builder<T> builder = DslElementModel.builder();
    builder.withModel(model)
      .withDsl(elementDsl)
      .withConfig(configuration);

    populateParameterizedElements(model, elementDsl, builder, configuration);
    return builder;
  }

  private void populateParameterizedElements(ParameterizedModel model, DslElementSyntax elementDsl,
                                             DslElementModel.Builder builder, ComponentConfiguration configuration) {

    Map<ComponentIdentifier, ComponentConfiguration> innerComponents = configuration.getNestedComponents().stream()
      .collect(toMap(ComponentConfiguration::getIdentifier, e -> e));

    Map<String, String> parameters = configuration.getParameters();

    List<ParameterModel> inlineGroupedParameters = model.getParameterGroupModels().stream()
      .filter(ParameterGroupModel::isShowInDsl)
      .peek(group -> addInlineGroup(elementDsl, innerComponents, parameters, group))
      .flatMap(g -> g.getParameterModels().stream())
      .collect(toList());

    model.getAllParameterModels().stream()
      .filter(p -> !inlineGroupedParameters.contains(p))
      .forEach(p -> addElementParameter(innerComponents, parameters, elementDsl, builder, p));
  }

  private void addInlineGroup(DslElementSyntax elementDsl, Map<ComponentIdentifier, ComponentConfiguration> innerComponents,
                              Map<String, String> parameters, ParameterGroupModel group) {
    elementDsl.getChild(group.getName())
      .ifPresent(groupDsl -> {
        ComponentConfiguration groupComponent = getIdentifier(groupDsl).map(innerComponents::get).orElse(null);

        if (groupComponent != null) {
          DslElementModel.Builder<ParameterGroupModel> groupElementBuilder = DslElementModel.<ParameterGroupModel>builder()
            .withModel(group)
            .withDsl(groupDsl)
            .withConfig(groupComponent);

          group.getParameterModels()
            .forEach(p -> addElementParameter(innerComponents, parameters, groupDsl, groupElementBuilder, p));
        }
      });
  }

  private void addElementParameter(Map<ComponentIdentifier, ComponentConfiguration> innerComponents,
                                   Map<String, String> parameters,
                                   DslElementSyntax groupDsl, DslElementModel.Builder<ParameterGroupModel> groupElementBuilder,
                                   ParameterModel p) {

    groupDsl.getContainedElement(p.getName())
      .ifPresent(pDsl -> {
        ComponentConfiguration paramComponent = getIdentifier(pDsl).map(innerComponents::get).orElse(null);

        if (!pDsl.isWrapped()) {
          String paramValue = pDsl.supportsAttributeDeclaration() ? parameters.get(pDsl.getAttributeName()) : null;
          if (paramComponent != null || paramValue != null) {
            DslElementModel.Builder<ParameterModel> paramElement =
              DslElementModel.<ParameterModel>builder().withModel(p).withDsl(pDsl);

            if (paramComponent != null) {
              paramElement.withConfig(paramComponent);

              if (paramComponent.getNestedComponents().size() > 0) {
                paramComponent.getNestedComponents().forEach(c -> this.resolve(c).ifPresent(paramElement::containing));
              }
            }

            groupElementBuilder.containing(paramElement.build());
          }
        } else {
          resolveWrappedElement(groupElementBuilder, p, pDsl, paramComponent);
        }

      });
  }

  private void resolveWrappedElement(DslElementModel.Builder<ParameterGroupModel> groupElementBuilder, ParameterModel p,
                                     DslElementSyntax pDsl, ComponentConfiguration paramComponent) {
    if (paramComponent != null) {
      DslElementModel.Builder<ParameterModel> paramElement =
        DslElementModel.<ParameterModel>builder().withModel(p).withDsl(pDsl).withConfig(paramComponent);

      if (paramComponent.getNestedComponents().size() > 0) {
        ComponentConfiguration wrappedComponent = paramComponent.getNestedComponents().get(0);
        this.resolve(wrappedComponent).ifPresent(paramElement::containing);
      }

      groupElementBuilder.containing(paramElement.build());
    }
  }

}
