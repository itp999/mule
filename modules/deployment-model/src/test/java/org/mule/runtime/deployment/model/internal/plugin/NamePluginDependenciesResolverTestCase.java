/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.runtime.deployment.model.internal.plugin;

import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.mule.runtime.deployment.model.internal.plugin.NamePluginDependenciesResolver.createResolutionErrorMessage;
import org.mule.runtime.deployment.model.api.plugin.ArtifactPluginDescriptor;
import org.mule.runtime.module.artifact.descriptor.ClassLoaderModel;
import org.mule.runtime.module.artifact.descriptor.ClassLoaderModel.ClassLoaderModelBuilder;
import org.mule.tck.junit4.AbstractMuleTestCase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class NamePluginDependenciesResolverTestCase extends AbstractMuleTestCase {


  private final ArtifactPluginDescriptor fooPlugin = new ArtifactPluginDescriptor("foo");
  private final ArtifactPluginDescriptor barPlugin = new ArtifactPluginDescriptor("bar");
  private final ArtifactPluginDescriptor bazPlugin = new ArtifactPluginDescriptor("baz");
  private final PluginDependenciesResolver dependenciesResolver = new NamePluginDependenciesResolver();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void resolvesIndependentPlugins() throws Exception {
    final List<ArtifactPluginDescriptor> pluginDescriptors = createPluginDescriptors(fooPlugin, barPlugin);

    final List<ArtifactPluginDescriptor> resolvedPluginDescriptors = dependenciesResolver.resolve(pluginDescriptors);

    assertResolvedPlugins(resolvedPluginDescriptors, barPlugin, fooPlugin);
  }

  @Test
  public void resolvesPluginOrderedDependency() throws Exception {
    final List<ArtifactPluginDescriptor> pluginDescriptors = createPluginDescriptors(fooPlugin, barPlugin);
    barPlugin.setClassLoaderModel(new ClassLoaderModelBuilder().dependingOn(singleton("foo")).build());

    final List<ArtifactPluginDescriptor> resolvedPluginDescriptors = dependenciesResolver.resolve(pluginDescriptors);

    assertResolvedPlugins(resolvedPluginDescriptors, fooPlugin, barPlugin);
  }

  @Test
  public void resolvesPluginDisorderedDependency() throws Exception {
    final List<ArtifactPluginDescriptor> pluginDescriptors = createPluginDescriptors(barPlugin, fooPlugin);
    barPlugin.setClassLoaderModel(new ClassLoaderModelBuilder().dependingOn(singleton("foo")).build());

    final List<ArtifactPluginDescriptor> resolvedPluginDescriptors = dependenciesResolver.resolve(pluginDescriptors);

    assertResolvedPlugins(resolvedPluginDescriptors, fooPlugin, barPlugin);
  }

  @Test
  public void detectsUnresolvablePluginDependency() throws Exception {
    final List<ArtifactPluginDescriptor> pluginDescriptors = createPluginDescriptors(fooPlugin);
    fooPlugin.setClassLoaderModel(new ClassLoaderModelBuilder().dependingOn(singleton("bar")).build());

    expectedException.expect(PluginResolutionError.class);
    expectedException.expectMessage(createResolutionErrorMessage(singletonList(fooPlugin), emptyList()));
    dependenciesResolver.resolve(pluginDescriptors);
  }

  @Test
  public void resolvesTransitiveDependencies() throws Exception {
    final List<ArtifactPluginDescriptor> pluginDescriptors = createPluginDescriptors(fooPlugin, barPlugin, bazPlugin);
    barPlugin.setClassLoaderModel(new ClassLoaderModelBuilder().dependingOn(singleton("baz")).build());
    bazPlugin.setClassLoaderModel(new ClassLoaderModelBuilder().dependingOn(singleton("foo")).build());

    final List<ArtifactPluginDescriptor> resolvedPluginDescriptors = dependenciesResolver.resolve(pluginDescriptors);

    assertResolvedPlugins(resolvedPluginDescriptors, fooPlugin, bazPlugin, barPlugin);
  }

  @Test
  public void resolvesMultipleDependencies() throws Exception {
    final List<ArtifactPluginDescriptor> pluginDescriptors = createPluginDescriptors(fooPlugin, barPlugin, bazPlugin);
    barPlugin.setClassLoaderModel(new ClassLoaderModelBuilder().dependingOn(singleton("baz")).build());
    bazPlugin.setClassLoaderModel(new ClassLoaderModelBuilder().dependingOn(singleton("foo")).build());

    final List<ArtifactPluginDescriptor> resolvedPluginDescriptors = dependenciesResolver.resolve(pluginDescriptors);

    assertResolvedPlugins(resolvedPluginDescriptors, fooPlugin, bazPlugin, barPlugin);
  }

  @Test
  public void sanitizesDependantPluginExportedPackages() throws Exception {
    fooPlugin.setClassLoaderModel(new ClassLoaderModelBuilder().exportingPackages(getFooExportedPackages()).build());


    barPlugin.setClassLoaderModel(new ClassLoaderModelBuilder().dependingOn(singleton("foo"))
        .exportingPackages(getBarExportedPackages()).build());

    final List<ArtifactPluginDescriptor> pluginDescriptors = createPluginDescriptors(fooPlugin, barPlugin);

    final List<ArtifactPluginDescriptor> resolvedPluginDescriptors = dependenciesResolver.resolve(pluginDescriptors);

    assertResolvedPlugins(resolvedPluginDescriptors, fooPlugin, barPlugin);
    assertPluginExportedPackages(fooPlugin, "org.foo", "org.foo.mule");
    assertPluginExportedPackages(barPlugin, "org.bar", "org.baz", "org.bar.mule");
  }

  @Test
  public void sanitizesTransitiveDependantPluginExportedPackages() throws Exception {
    fooPlugin.setClassLoaderModel(new ClassLoaderModelBuilder().exportingPackages(getFooExportedPackages()).build());

    bazPlugin.setClassLoaderModel(new ClassLoaderModelBuilder().exportingPackages(getBazExportedPackages())
        .dependingOn(singleton("foo")).build());

    barPlugin.setClassLoaderModel(new ClassLoaderModel.ClassLoaderModelBuilder().exportingPackages(getBarExportedPackages())
        .dependingOn(singleton("baz")).build());

    final List<ArtifactPluginDescriptor> pluginDescriptors = createPluginDescriptors(fooPlugin, barPlugin, bazPlugin);

    final List<ArtifactPluginDescriptor> resolvedPluginDescriptors = dependenciesResolver.resolve(pluginDescriptors);

    assertResolvedPlugins(resolvedPluginDescriptors, fooPlugin, bazPlugin, barPlugin);
    assertPluginExportedPackages(fooPlugin, "org.foo", "org.foo.mule");
    assertPluginExportedPackages(bazPlugin, "org.baz");
    assertPluginExportedPackages(barPlugin, "org.bar", "org.bar.mule");
  }

  private Set<String> getBarExportedPackages() {
    final Set<String> barExportedClassPackages = new HashSet<>();
    barExportedClassPackages.add("org.bar");
    barExportedClassPackages.add("org.baz");
    barExportedClassPackages.add("org.foo");
    barExportedClassPackages.add("org.foo.mule");
    barExportedClassPackages.add("org.bar.mule");
    return barExportedClassPackages;
  }

  private Set<String> getFooExportedPackages() {
    final Set<String> fooExportedClassPackages = new HashSet<>();
    fooExportedClassPackages.add("org.foo");
    fooExportedClassPackages.add("org.foo.mule");
    return fooExportedClassPackages;
  }

  private Set<String> getBazExportedPackages() {
    final Set<String> bazExportedClassPackages = new HashSet<>();
    bazExportedClassPackages.add("org.baz");
    return bazExportedClassPackages;
  }

  private void assertResolvedPlugins(List<ArtifactPluginDescriptor> resolvedPluginDescriptors,
                                     ArtifactPluginDescriptor... expectedPluginDescriptors) {
    assertThat(resolvedPluginDescriptors.size(), equalTo(expectedPluginDescriptors.length));

    for (int i = 0; i < resolvedPluginDescriptors.size(); i++) {
      assertThat(resolvedPluginDescriptors.get(i), equalTo(expectedPluginDescriptors[i]));
    }
  }

  private void assertPluginExportedPackages(ArtifactPluginDescriptor pluginDescriptor, String... exportedPackages) {
    assertThat(pluginDescriptor.getClassLoaderModel().getExportedPackages().size(), equalTo(exportedPackages.length));
    assertThat(pluginDescriptor.getClassLoaderModel().getExportedPackages(), containsInAnyOrder(exportedPackages));
  }

  private List<ArtifactPluginDescriptor> createPluginDescriptors(ArtifactPluginDescriptor... descriptors) {
    final List<ArtifactPluginDescriptor> result = new ArrayList<>();
    for (ArtifactPluginDescriptor descriptor : descriptors) {
      result.add(descriptor);
    }

    return result;
  }
}
