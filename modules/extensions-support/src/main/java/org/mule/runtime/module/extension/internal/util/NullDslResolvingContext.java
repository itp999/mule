/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.util;

import static java.util.Collections.emptySet;
import org.mule.metadata.api.model.ObjectType;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.extension.api.dsl.DslResolvingContext;
import org.mule.runtime.extension.api.type.TypeCatalog;

import java.util.Optional;
import java.util.Set;

/**
 * //TODO
 */
public class NullDslResolvingContext implements DslResolvingContext {

  @Override
  public Optional<ExtensionModel> getExtension(String name) {
    return Optional.empty();
  }

  @Override
  public Set<ExtensionModel> getExtensions() {
    return emptySet();
  }

  @Override
  public TypeCatalog getTypeCatalog() {
    return new TypeCatalog() {

      @Override
      public Optional<ObjectType> getType(String typeId) {
        return Optional.empty();
      }

      @Override
      public Set<ObjectType> getTypes() {
        return emptySet();
      }

      @Override
      public Set<ObjectType> getSubTypes(ObjectType type) {
        return emptySet();
      }

      @Override
      public Set<ObjectType> getSuperTypes(ObjectType type) {
        return emptySet();
      }
    };
  }
}
