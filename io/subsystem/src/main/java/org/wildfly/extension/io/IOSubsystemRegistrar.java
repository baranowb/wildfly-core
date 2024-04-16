/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.extension.io;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.ResourceRegistration;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SubsystemRegistration;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.descriptions.ParentResourceDescriptionResolver;
import org.jboss.as.controller.descriptions.SubsystemResourceDescriptionResolver;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.wildfly.common.function.Functions;
import org.wildfly.io.IOServiceDescriptor;
import org.wildfly.subsystem.resource.ManagementResourceRegistrar;
import org.wildfly.subsystem.resource.ManagementResourceRegistrationContext;
import org.wildfly.subsystem.resource.ResourceDescriptor;
import org.wildfly.subsystem.resource.SubsystemResourceDefinitionRegistrar;
import org.wildfly.subsystem.resource.capability.CapabilityReferenceRecorder;
import org.wildfly.subsystem.resource.operation.ResourceOperationRuntimeHandler;
import org.wildfly.subsystem.service.ResourceServiceConfigurator;
import org.wildfly.subsystem.service.ResourceServiceInstaller;
import org.wildfly.subsystem.service.ServiceDependency;
import org.wildfly.subsystem.service.capability.CapabilityServiceInstaller;

/**
 * @author <a href="mailto:tomaz.cerar@redhat.com">Tomaz Cerar</a> (c) 2013 Red Hat Inc.
 */
class IOSubsystemRegistrar implements SubsystemResourceDefinitionRegistrar, ResourceServiceConfigurator {

    static final String NAME = "io";
    static final PathElement PATH = SubsystemResourceDefinitionRegistrar.pathElement(NAME);
    static final ParentResourceDescriptionResolver RESOLVER = new SubsystemResourceDescriptionResolver(NAME, IOSubsystemRegistrar.class);

    static final RuntimeCapability<Void> MAX_THREADS_CAPABILITY = RuntimeCapability.Builder.of(IOServiceDescriptor.MAX_THREADS).build();

    static final RuntimeCapability<Void> DEFAULT_WORKER_CAPABILITY = RuntimeCapability.Builder.of(IOServiceDescriptor.DEFAULT_WORKER).build();

    static final ModelNode LEGACY_DEFAULT_WORKER = new ModelNode("default");

    static final AttributeDefinition DEFAULT_WORKER = new SimpleAttributeDefinitionBuilder("default-worker", ModelType.STRING)
            .setRequired(false)
            .setCapabilityReference(CapabilityReferenceRecorder.builder(DEFAULT_WORKER_CAPABILITY, IOServiceDescriptor.WORKER).build())
            .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .build();

    // Tracks max-threads for all workers
    private final AtomicInteger maxThreads = new AtomicInteger();

    @Override
    public ManagementResourceRegistration register(SubsystemRegistration parent, ManagementResourceRegistrationContext context) {
        ManagementResourceRegistration registration = parent.registerSubsystemModel(ResourceDefinition.builder(ResourceRegistration.of(PATH), RESOLVER).build());

        ResourceDescriptor descriptor = ResourceDescriptor.builder(RESOLVER)
                .addAttributes(List.of(DEFAULT_WORKER))
                .addCapabilities(List.of(DEFAULT_WORKER_CAPABILITY, MAX_THREADS_CAPABILITY))
                .withRuntimeHandler(ResourceOperationRuntimeHandler.configureParentService(this))
                .build();
        ManagementResourceRegistrar.of(descriptor).register(registration);

        registration.registerSubModel(new WorkerResourceDefinition(this.maxThreads));
        registration.registerSubModel(new BufferPoolResourceDefinition());

        return registration;
    }

    @Override
    public ResourceServiceInstaller configure(OperationContext context, ModelNode model) throws OperationFailedException {
        ModelNode workers = model.get(WorkerResourceDefinition.PATH.getKey());
        WorkerAdd.checkWorkerConfiguration(context, workers);

        List<ResourceServiceInstaller> installers = new ArrayList<>(2);
        installers.add(CapabilityServiceInstaller.builder(IOSubsystemRegistrar.MAX_THREADS_CAPABILITY, AtomicInteger::intValue, Functions.constantSupplier(this.maxThreads)).build());

        String defaultWorker = IOSubsystemRegistrar.DEFAULT_WORKER.resolveModelAttribute(context, model).asStringOrNull();
        if (defaultWorker != null) {
            installers.add(CapabilityServiceInstaller.builder(IOSubsystemRegistrar.DEFAULT_WORKER_CAPABILITY, ServiceDependency.on(IOServiceDescriptor.WORKER, defaultWorker)).build());
        }

        return ResourceServiceInstaller.combine(installers);
    }
}
