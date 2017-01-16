/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.spring.dsl.model.internal;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;
import static org.mule.metadata.api.utils.MetadataTypeUtils.getLocalPart;
import static org.mule.runtime.api.dsl.DslConstants.VALUE_ATTRIBUTE_NAME;
import static org.mule.runtime.extension.api.ExtensionConstants.INFRASTRUCTURE_NAMES;
import static org.mule.runtime.extension.internal.dsl.syntax.DslSyntaxUtils.getId;
import static org.mule.runtime.extension.internal.dsl.syntax.DslSyntaxUtils.getIdentifier;
import org.mule.metadata.api.model.ArrayType;
import org.mule.metadata.api.model.DictionaryType;
import org.mule.metadata.api.model.MetadataType;
import org.mule.metadata.api.model.ObjectFieldType;
import org.mule.metadata.api.model.ObjectType;
import org.mule.metadata.api.visitor.MetadataTypeVisitor;
import org.mule.runtime.api.app.config.ComponentConfiguration;
import org.mule.runtime.api.app.config.ComponentIdentifier;
import org.mule.runtime.api.app.declaration.ComponentElementDeclaration;
import org.mule.runtime.api.app.declaration.ConfigurationElementDeclaration;
import org.mule.runtime.api.app.declaration.ConnectionElementDeclaration;
import org.mule.runtime.api.app.declaration.ParameterElementDeclaration;
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
import org.mule.runtime.api.util.Preconditions;
import org.mule.runtime.api.util.Reference;
import org.mule.runtime.config.spring.dsl.model.DslElementModel;
import org.mule.runtime.config.spring.dsl.model.DslElementModelFactory;
import org.mule.runtime.extension.api.dsl.DslResolvingContext;
import org.mule.runtime.extension.api.dsl.syntax.DslElementSyntax;
import org.mule.runtime.extension.api.dsl.syntax.resolver.DslSyntaxResolver;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of a {@link DslElementModelFactory}
 *
 * @since 1.0
 */
class DeclarationBasedElementModelFactory {

  private final DslResolvingContext context;
  private Map<ExtensionModel, DslSyntaxResolver> resolvers;
  private ExtensionModel currentExtension;
  private DslSyntaxResolver dsl;

  DeclarationBasedElementModelFactory(DslResolvingContext context, Map<ExtensionModel, DslSyntaxResolver> resolvers) {
    this.context = context;
    this.resolvers = resolvers;
  }

  public <T extends org.mule.runtime.api.meta.model.ComponentModel> DslElementModel<T> create(
    ComponentElementDeclaration componentDeclaration) {
    setupCurrentExtensionContext(componentDeclaration.getDeclaringExtension());


    Reference<org.mule.runtime.api.meta.model.ComponentModel> component = new Reference<>();
    new ExtensionWalker() {

      @Override
      protected void onOperation(HasOperationModels owner, OperationModel model) {
        if (model.getName().equals(componentDeclaration.getName())) {
          component.set(model);
          stop();
        }
      }

      @Override
      protected void onSource(HasSourceModels owner, SourceModel model) {
        if (model.getName().equals(componentDeclaration.getName())) {
          component.set(model);
          stop();
        }
      }

    }.walk(currentExtension);

    if (component.get() == null) {
      throw new IllegalArgumentException(componentDeclaration.getName());
    }

    DslElementSyntax configDsl = dsl.resolve(component.get());

    ComponentConfiguration.Builder configuration = ComponentConfiguration.builder()
      .withIdentifier(asIdentifier(configDsl));

    DslElementModel.Builder<? extends org.mule.runtime.api.meta.model.ComponentModel> element =
      createParameterizedElementModel(component.get(), configDsl, componentDeclaration, configuration);

    return (DslElementModel<T>) element.withConfig(configuration.build()).build();
  }

  public DslElementModel<ConfigurationModel> create(ConfigurationElementDeclaration configurationDeclaration) {
    setupCurrentExtensionContext(configurationDeclaration.getDeclaringExtension());

    ConfigurationModel model = currentExtension.getConfigurationModel(configurationDeclaration.getName())
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

  private void setupCurrentExtensionContext(String extension) {
    this.currentExtension = context.getExtension(extension).orElseThrow(() -> new IllegalArgumentException());
    this.dsl = resolvers.get(currentExtension);
  }

  private void addConnectionProvider(ConnectionElementDeclaration connection,
                                     ConfigurationModel model,
                                     ComponentConfiguration.Builder configuration,
                                     DslElementModel.Builder<ConfigurationModel> configElement) {

    concat(model.getConnectionProviders().stream(), currentExtension.getConnectionProviders()
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
    DslElementModel.Builder<T> parentElement = DslElementModel.<T>builder()
      .withModel(model)
      .withDsl(elementDsl);

    List<ParameterModel> inlineGroupedParameters = model.getParameterGroupModels().stream()
      .filter(ParameterGroupModel::isShowInDsl)
      .peek(group -> addInlineGroupElement(group, elementDsl, parentConfig, parentElement, declaration))
      .flatMap(g -> g.getParameterModels().stream())
      .collect(toList());


    List<ParameterModel> nonGroupedParameters = model.getAllParameterModels().stream()
      .filter(p -> !inlineGroupedParameters.contains(p))
      .collect(toList());

    addAllDeclaredParameters(nonGroupedParameters, declaration.getParameters(), elementDsl, parentConfig, parentElement);

    return parentElement;
  }

  private void addAllDeclaredParameters(List<ParameterModel> parameterModels,
                                        List<ParameterElementDeclaration> parameterDeclarations,
                                        DslElementSyntax parentDsl,
                                        ComponentConfiguration.Builder parentConfig,
                                        DslElementModel.Builder parentElement) {

    parameterModels.forEach(paramModel -> parameterDeclarations.stream()
      .filter(declared -> declared.getName().equals(paramModel.getName()))
      .findFirst()
      .ifPresent(paramDeclaration -> parentDsl.getContainedElement(paramModel.getName())
        .ifPresent(
          paramDsl -> addParameterDeclaration(paramDeclaration, paramModel, paramDsl, parentConfig,
                                              parentElement))));
  }

  private <T extends ParameterizedModel> void addInlineGroupElement(ParameterGroupModel group,
                                                                    DslElementSyntax elementDsl,
                                                                    ComponentConfiguration.Builder parentConfig,
                                                                    DslElementModel.Builder<T> parentElement,
                                                                    ParameterizedElementDeclaration declaration) {
    elementDsl.getChild(group.getName())
      .ifPresent(groupDsl -> {
        DslElementModel.Builder<ParameterGroupModel> groupElementBuilder = DslElementModel.<ParameterGroupModel>builder()
          .withModel(group)
          .withDsl(groupDsl);

        ComponentConfiguration.Builder groupBuilder = ComponentConfiguration.builder().withIdentifier(asIdentifier(groupDsl));

        addAllDeclaredParameters(group.getParameterModels(), declaration.getParameters(), groupDsl, groupBuilder,
                                 groupElementBuilder);

        ComponentConfiguration groupConfig = groupBuilder.build();
        groupElementBuilder.withConfig(groupConfig);

        parentConfig.withNestedComponent(groupConfig);
        parentElement.containing(groupElementBuilder.build());
      });
  }

  private void addParameterDeclaration(ParameterElementDeclaration parameter,
                                       ParameterModel parameterModel,
                                       DslElementSyntax paramDsl,
                                       final ComponentConfiguration.Builder parentConfig,
                                       final DslElementModel.Builder parentElement) {

    if (INFRASTRUCTURE_NAMES.contains(parameter.getName())) {
      //TODO

      return;
    }

    parameter.getValue().accept(new ParameterValueVisitor() {

      @Override
      public void visitSimpleValue(String value) {
        if (paramDsl.supportsAttributeDeclaration()) {
          // attribute parameters imply no further nesting in the configs
          parentConfig.withParameter(paramDsl.getAttributeName(), value);
          parentElement.containing(DslElementModel.<ParameterModel>builder()
                                     .withModel(parameterModel)
                                     .withDsl(paramDsl)
                                     .build());

        } else {
          // we are in the content case, so we have one more nesting level
          ComponentConfiguration parameterConfig = ComponentConfiguration.builder()
            .withIdentifier(asIdentifier(paramDsl))
            .withValue(value)
            .build();

          parentConfig.withNestedComponent(parameterConfig);

          parentElement.containing(DslElementModel.<ParameterModel>builder()
                                     .withModel(parameterModel)
                                     .withDsl(paramDsl)
                                     .withConfig(parameterConfig)
                                     .build());
        }
      }

      @Override
      public void visitListValue(ParameterListValue list) {
        Preconditions.checkArgument(paramDsl.supportsChildDeclaration(), "Cannot build nested ");

        // the parameter is of list type, so we have nested elements
        // we'll resolve this based on the type of the parameter, since no
        // further model information is available
        createList(list, paramDsl, (ArrayType) parameterModel.getType(), parentConfig, parentElement);
      }

      @Override
      public void visitObjectValue(ParameterObjectValue objectValue) {
        Preconditions.checkArgument(paramDsl.supportsChildDeclaration(), "Cannot build nested ");

        if (!paramDsl.isWrapped()) {
          // the parameter is of a complex object type, so we have both nested elements
          // and attributes as values of this element.
          // we'll resolve this based on the type of the parameter, since no
          // further model information is available
          createObject((ObjectType) parameterModel.getType(), objectValue, paramDsl, parentConfig, parentElement);

        } else {
          // the parameter is of an extensible object type, so we need a wrapper element
          // before defining the actual object structure
          // we'll resolve this structure based on the configured type, since no
          // further model information is available

          DslElementModel.Builder<ParameterModel> wrapperElement = DslElementModel.<ParameterModel>builder()
            .withModel(parameterModel)
            .withDsl(paramDsl);

          ComponentConfiguration.Builder wrapperConfig = ComponentConfiguration.builder()
            .withIdentifier(asIdentifier(paramDsl));

          ObjectType nestedElementType;
          if (objectValue.getTypeId() == null || objectValue.getTypeId().trim().isEmpty() ||
            objectValue.getTypeId().equals(getId(parameterModel.getType()))) {

            nestedElementType = (ObjectType) parameterModel.getType();

          } else {
            nestedElementType = lookupType(objectValue);
          }

          dsl.resolve(nestedElementType)
            .ifPresent(typeDsl -> createObject(nestedElementType, objectValue, typeDsl, wrapperConfig, wrapperElement));

          ComponentConfiguration result = wrapperConfig.build();

          parentConfig.withNestedComponent(result);
          parentElement.containing(wrapperElement.withConfig(result).build());
        }
      }
    });
  }

  private ObjectType lookupType(ParameterObjectValue objectValue) {
    ObjectType nestedElementType;
    nestedElementType = context.getTypeCatalog().getType(objectValue.getTypeId())
      .orElseThrow(() -> new IllegalArgumentException(format("Could not find Type with ID '%s' in the current context",
                                                             objectValue.getTypeId())));
    return nestedElementType;
  }

  private void createListItemConfig(MetadataType itemValueType,
                                    ParameterValue itemValue,
                                    DslElementSyntax itemDsl,
                                    ComponentConfiguration.Builder parentConfig,
                                    DslElementModel.Builder parentElement) {

    itemValue.accept(new ParameterValueVisitor() {

      @Override
      public void visitSimpleValue(String value) {
        ComponentConfiguration item = ComponentConfiguration.builder()
          .withIdentifier(asIdentifier(itemDsl))
          .withParameter(VALUE_ATTRIBUTE_NAME, value)
          .build();

        parentConfig.withNestedComponent(item);
        parentElement.containing(DslElementModel.<MetadataType>builder()
                                   .withModel(itemValueType)
                                   .withDsl(itemDsl)
                                   .withConfig(item)
                                   .build());
      }

      @Override
      public void visitListValue(ParameterListValue list) {
        DslElementModel.Builder<MetadataType> itemElement = DslElementModel.<MetadataType>builder()
          .withModel(itemValueType)
          .withDsl(itemDsl);

        ComponentConfiguration.Builder itemConfig = ComponentConfiguration.builder()
          .withIdentifier(asIdentifier(itemDsl));

        MetadataType genericType = ((ArrayType) itemValueType).getType();
        itemDsl.getGeneric(genericType)
          .ifPresent(genericDsl -> list.getValues()
            .forEach(value -> createListItemConfig(genericType, value, genericDsl, itemConfig, itemElement)));

        ComponentConfiguration result = itemConfig.build();

        parentConfig.withNestedComponent(result);
        parentElement.containing(itemElement.withConfig(result).build());
      }

      @Override
      public void visitObjectValue(ParameterObjectValue objectValue) {
        itemValueType.accept(new MetadataTypeVisitor() {

          @Override
          public void visitObject(ObjectType objectType) {
            createObject(objectType, objectValue, itemDsl, parentConfig, parentElement);
          }

          @Override
          public void visitDictionary(DictionaryType dictionaryType) {
            // TODO
          }
        });
      }
    });
  }


  private void addObjectField(MetadataType fieldType, ParameterValue fieldValue,
                              DslElementSyntax fieldDsl,
                              ComponentConfiguration.Builder objectConfig,
                              DslElementModel.Builder<MetadataType> objectElement) {

    fieldValue.accept(new ParameterValueVisitor() {

      @Override
      public void visitSimpleValue(String value) {
        if (fieldDsl.supportsAttributeDeclaration()) {
          objectConfig.withParameter(fieldDsl.getAttributeName(), value);
          objectElement.containing(DslElementModel.builder()
                                     .withModel(fieldType)
                                     .withDsl(fieldDsl)
                                     .build());

        } else {
          objectConfig.withNestedComponent(ComponentConfiguration.builder()
                                             .withIdentifier(asIdentifier(fieldDsl))
                                             .withValue(value)
                                             .build());
        }
      }

      @Override
      public void visitListValue(ParameterListValue list) {
        createList(list, fieldDsl, (ArrayType) fieldType, objectConfig, objectElement);
      }

      @Override
      public void visitObjectValue(ParameterObjectValue objectValue) {

        fieldType.accept(new MetadataTypeVisitor() {

          @Override
          public void visitObject(ObjectType objectType) {
            createObject(objectType, objectValue, fieldDsl, objectConfig, objectElement);
          }

          @Override
          public void visitDictionary(DictionaryType dictionaryType) {
            //TODO
          }
        });

      }
    });
  }

  private void createList(ParameterListValue list,
                          DslElementSyntax listDsl,
                          ArrayType listType,
                          ComponentConfiguration.Builder parentConfig,
                          DslElementModel.Builder parentElement) {

    final DslElementModel.Builder<MetadataType> listElement = DslElementModel.<MetadataType>builder()
      .withModel(listType)
      .withDsl(listDsl);

    final ComponentConfiguration.Builder listConfig = ComponentConfiguration.builder()
      .withIdentifier(asIdentifier(listDsl));

    final MetadataType itemType = listType.getType();
    listDsl.getGeneric(itemType)
      .ifPresent(itemDsl -> list.getValues()
        .forEach(value -> createListItemConfig(itemType, value, itemDsl, listConfig, listElement)));

    ComponentConfiguration result = listConfig.build();

    parentConfig.withNestedComponent(result);
    parentElement.containing(listElement.withConfig(result).build());
  }

  private void createObject(ObjectType objectType,
                            ParameterObjectValue objectValue,
                            DslElementSyntax objectDsl,
                            ComponentConfiguration.Builder parentConfig,
                            DslElementModel.Builder parentElement) {

    ComponentConfiguration.Builder objectConfig = ComponentConfiguration.builder()
      .withIdentifier(asIdentifier(objectDsl));

    DslElementModel.Builder<MetadataType> objectElement = DslElementModel.<MetadataType>builder()
      .withModel(objectType)
      .withDsl(objectDsl);

    Collection<ObjectFieldType> fields = objectType.getFields();

    objectValue.getParameters()
      .forEach((name, value) -> fields.stream()
        .filter(f -> getLocalPart(f).equals(name))
        .findFirst()
        .ifPresent(field -> objectDsl.getContainedElement(name)
          .ifPresent(nestedDsl -> addObjectField(field.getValue(), value, nestedDsl, objectConfig, objectElement))));

    ComponentConfiguration result = objectConfig.build();
    parentConfig.withNestedComponent(result);

    parentElement.containing(objectElement.withConfig(result).build());
  }

  private ComponentIdentifier asIdentifier(DslElementSyntax fieldDsl) {
    return getIdentifier(fieldDsl).orElseThrow(() -> new IllegalArgumentException());
  }

}
