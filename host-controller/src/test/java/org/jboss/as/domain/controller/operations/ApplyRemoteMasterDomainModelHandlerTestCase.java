/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.domain.controller.operations;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DOMAIN_MODEL;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXTENSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.INTERFACE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT_CLIENT_CONTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PATH;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PROFILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ROLLOUT_PLANS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_CONFIG;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SOCKET_BINDING_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SYSTEM_PROPERTY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.RunningMode;
import org.jboss.as.controller.RunningModeControl;
import org.jboss.as.controller.access.management.WritableAuthorizerConfiguration;
import org.jboss.as.controller.access.rbac.StandardRBACAuthorizer;
import org.jboss.as.controller.extension.ExtensionRegistry;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.host.controller.ignored.IgnoredDomainResourceRegistry;
import org.jboss.as.management.client.content.ManagedDMRContentTypeResource;
import org.jboss.dmr.ModelNode;
import org.junit.Test;

/**
 * @author John Bailey
 */
public class ApplyRemoteMasterDomainModelHandlerTestCase extends AbstractOperationTestCase {

    private final ApplyExtensionsHandler extensionHandler = new ApplyExtensionsHandler(new ExtensionRegistry(
            ProcessType.HOST_CONTROLLER, new RunningModeControl(RunningMode.NORMAL)), HOST_INFO, new IgnoredDomainResourceRegistry(HOST_INFO)) {
        @Override
        protected void initializeExtension(String module) {
            // nothing here
        }
    };
    WritableAuthorizerConfiguration authorizerConfiguration = new WritableAuthorizerConfiguration(StandardRBACAuthorizer.AUTHORIZER_DESCRIPTION);
    private final ApplyRemoteMasterDomainModelHandler handler =
            new ApplyRemoteMasterDomainModelHandler(null, null, HOST_INFO, new IgnoredDomainResourceRegistry(HOST_INFO), authorizerConfiguration);

    @Test
    public void testNoChanges() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        operation.get(DOMAIN_MODEL).setEmptyList();

        executeAndVerify(root, operation);
    }

    @Test
    public void testBooting() throws Exception {
        final ModelNode operation = new ModelNode();
        operation.get(DOMAIN_MODEL).setEmptyList();
        final OperationContext operationContext = getOperationContext(true);
        handler.execute(operationContext, operation);
    }

    @Test
    public void testExtensionAdd() throws Exception {
        final ModelNode operation = new ModelNode();
        final ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(EXTENSION, "org.jboss.extension")).toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);
        final MockOperationContext operationContext = getOperationContext();
        operationContext.expectStep(PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")));
        operationContext.expectStep(PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
        extensionHandler.execute(operationContext, operation);
        operationContext.verify();
    }

    @Test
    public void testExtensionRemove() throws Exception {
        final ModelNode operation = new ModelNode();
        operation.get(DOMAIN_MODEL).setEmptyList();
        final MockOperationContext operationContext = getOperationContext();
        operationContext.root.registerChild(PathElement.pathElement(EXTENSION, "org.jboss.extension"), Resource.Factory.create());
        operationContext.expectStep(PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")));
        operationContext.expectStep(PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
        extensionHandler.execute(operationContext, operation);
        operationContext.verify();
    }

    @Test
    public void testPathAdd() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        final ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(PATH, "some-path")).toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")),
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testPathRemove() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        operation.get(DOMAIN_MODEL).setEmptyList();

        root.registerChild(PathElement.pathElement(PATH, "some-path"), Resource.Factory.create());

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")),
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testPathChange() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        final ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(PATH, "some-path")).toModelNode());
        final ModelNode path = new ModelNode();
        path.set("some path");
        change.get("domain-resource-model").set(path);
        operation.get(DOMAIN_MODEL).add(change);

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")),
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testSystemPropertyAdd() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SYSTEM_PROPERTY, "some-property")).toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);
        change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "group-one")).toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "group-two")).toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")),
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testSystemPropertyRemove() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        operation.get(DOMAIN_MODEL).setEmptyList();

        root.registerChild(PathElement.pathElement(SERVER_GROUP, "group-one"), Resource.Factory.create());
        root.registerChild(PathElement.pathElement(SYSTEM_PROPERTY, "some-property"), Resource.Factory.create());

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")));
    }

    @Test
    public void testSystemPropertyChange() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        final ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SYSTEM_PROPERTY, "some-property")).toModelNode());
        final ModelNode property = new ModelNode();
        property.set("some property");
        change.get("domain-resource-model").set(property);
        operation.get(DOMAIN_MODEL).add(change);

        root.registerChild(PathElement.pathElement(SERVER_GROUP, "group-two"), Resource.Factory.create());
        root.registerChild(PathElement.pathElement(SYSTEM_PROPERTY, "some-property"), Resource.Factory.create());

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testProfileAdd() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(PROFILE, "some-profile")).toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);
        change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "group-one")).toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "group-two")).toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")),
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testProfileRemove() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        operation.get(DOMAIN_MODEL).setEmptyList();

        root.registerChild(PathElement.pathElement(PROFILE, "some-profile"), Resource.Factory.create());
        final Resource serverGroupResource = Resource.Factory.create();
        serverGroupResource.getModel().get(PROFILE).set("some-profile");
        root.registerChild(PathElement.pathElement(SERVER_GROUP, "group-one"), serverGroupResource);

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")));
    }

    @Test
    public void testProfileChange() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        final ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(PROFILE, "some-profile")).toModelNode());
        final ModelNode property = new ModelNode();
        property.set("some profile");
        change.get("domain-resource-model").set(property);
        operation.get(DOMAIN_MODEL).add(change);

        root.registerChild(PathElement.pathElement(PROFILE, "some-profile"), Resource.Factory.create());
        final Resource serverGroupResource = Resource.Factory.create();
        serverGroupResource.getModel().get(PROFILE).set("some-profile");
        root.registerChild(PathElement.pathElement(SERVER_GROUP, "group-two"), serverGroupResource);

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testInterfaceAdd() throws Exception {
        final Resource root = createRootResource();

        final ModelNode operation = new ModelNode();
        final ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(INTERFACE, "some-interface")).toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")),
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testInterfaceAddWithServerOverride() throws Exception {
        final Resource root = createRootResource();

        final ModelNode operation = new ModelNode();
        final ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(INTERFACE, "some-interface")).toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);

        root.getChild(PathElement.pathElement(HOST, "localhost")).getChild(PathElement.pathElement(SERVER_CONFIG, "server-one")).registerChild(PathElement.pathElement(INTERFACE, "some-interface"), Resource.Factory.create());

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testInterfaceRemove() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        operation.get(DOMAIN_MODEL).setEmptyList();

        root.registerChild(PathElement.pathElement(INTERFACE, "some-interface"), Resource.Factory.create());
        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")),
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testInterfaceChange() throws Exception {
        final Resource root = createRootResource();

        final ModelNode operation = new ModelNode();
        final ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(INTERFACE, "some-interface")).toModelNode());
        final ModelNode property = new ModelNode();
        property.set("some interface");
        change.get("domain-resource-model").set(property);
        operation.get(DOMAIN_MODEL).add(change);

        root.registerChild(PathElement.pathElement(INTERFACE, "some-interface"), Resource.Factory.create());

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")),
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testSocketBindingAdd() throws Exception {
        final Resource root = createRootResource();

        final ModelNode operation = new ModelNode();
        ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SOCKET_BINDING_GROUP, "some-binding")).toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);

        change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "group-one")).toModelNode());
        final ModelNode serverConfig = new ModelNode();
        serverConfig.get(SOCKET_BINDING_GROUP).set("some-binding");
        change.get("domain-resource-model").set(serverConfig);
        operation.get(DOMAIN_MODEL).add(change);

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")));
    }

    @Test
    public void testSocketBindingChangeWithServerOverride() throws Exception {
        final Resource root = createRootResource();

        final ModelNode operation = new ModelNode();
        ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SOCKET_BINDING_GROUP, "some-binding")).toModelNode());
        final ModelNode newBinding = new ModelNode();
        newBinding.set("blahh");
        change.get("domain-resource-model").set(newBinding);
        operation.get(DOMAIN_MODEL).add(change);

        change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "group-one")).toModelNode());
        ModelNode serverConfig = new ModelNode();
        serverConfig.get(SOCKET_BINDING_GROUP).set("some-binding");
        change.get("domain-resource-model").set(serverConfig);
        operation.get(DOMAIN_MODEL).add(change);

        change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "group-two")).toModelNode());
        serverConfig = new ModelNode();
        serverConfig.get(SOCKET_BINDING_GROUP).set("some-binding");
        change.get("domain-resource-model").set(serverConfig);
        operation.get(DOMAIN_MODEL).add(change);

        final Resource groupOneResource = Resource.Factory.create();
        root.registerChild(PathElement.pathElement(SERVER_GROUP, "group-one"), groupOneResource);
        groupOneResource.getModel().get(SOCKET_BINDING_GROUP).set("some-binding");
        final Resource groupTwoResource = Resource.Factory.create();
        root.registerChild(PathElement.pathElement(SERVER_GROUP, "group-two"), groupTwoResource);
        groupTwoResource.getModel().get(SOCKET_BINDING_GROUP).set("some-binding");

        root.getChild(PathElement.pathElement(HOST, "localhost")).getChild(PathElement.pathElement(SERVER_CONFIG, "server-one")).getModel().get(SOCKET_BINDING_GROUP).set("other-binding");

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testSocketBindingRemove() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        operation.get(DOMAIN_MODEL).setEmptyList();

        final Resource groupOneResource = Resource.Factory.create();
        root.registerChild(PathElement.pathElement(SERVER_GROUP, "group-one"), groupOneResource);
        groupOneResource.getModel().get(SOCKET_BINDING_GROUP).set("some-binding");
        final Resource groupTwoResource = Resource.Factory.create();
        root.registerChild(PathElement.pathElement(SERVER_GROUP, "group-two"), groupTwoResource);
        groupTwoResource.getModel().get(SOCKET_BINDING_GROUP).set("some-binding");

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")),
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

    @Test
    public void testSocketBindingChange() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SOCKET_BINDING_GROUP, "some-binding")).toModelNode());
        final ModelNode newBinding = new ModelNode();
        newBinding.set("blahh");
        change.get("domain-resource-model").set(newBinding);
        operation.get(DOMAIN_MODEL).add(change);

        change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "group-one")).toModelNode());
        ModelNode serverConfig = new ModelNode();
        serverConfig.get(SOCKET_BINDING_GROUP).set("some-binding");
        change.get("domain-resource-model").set(serverConfig);
        operation.get(DOMAIN_MODEL).add(change);

        change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "group-two")).toModelNode());
        serverConfig = new ModelNode();
        serverConfig.get(SOCKET_BINDING_GROUP).set("some-binding");
        change.get("domain-resource-model").set(serverConfig);
        operation.get(DOMAIN_MODEL).add(change);


        final Resource groupOneResource = Resource.Factory.create();
        root.registerChild(PathElement.pathElement(SERVER_GROUP, "group-one"), groupOneResource);
        groupOneResource.getModel().get(SOCKET_BINDING_GROUP).set("some-binding");
        final Resource groupTwoResource = Resource.Factory.create();
        root.registerChild(PathElement.pathElement(SERVER_GROUP, "group-two"), groupTwoResource);
        groupTwoResource.getModel().get(SOCKET_BINDING_GROUP).set("some-binding");

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")),
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-three")));
    }

     @Test
    public void testServerGroupAdd() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        final ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "group-one")).toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);

         executeAndVerify(root, operation,
                 PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")));
    }

    @Test
    public void testServerGroupRemove() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        operation.get(DOMAIN_MODEL).setEmptyList();

        root.registerChild(PathElement.pathElement(SERVER_GROUP, "group-one"), Resource.Factory.create());

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")));
    }

    @Test
    public void testServerGroupChange() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        final ModelNode change = new ModelNode();
        change.get("domain-resource-address").set(PathAddress.pathAddress(PathElement.pathElement(SERVER_GROUP, "group-one")).toModelNode());
        final ModelNode group = new ModelNode();
        group.get("Some prop").set("some value");
        change.get("domain-resource-model").set(group);
        operation.get(DOMAIN_MODEL).add(change);

        root.registerChild(PathElement.pathElement(SERVER_GROUP, "group-one"), Resource.Factory.create());

        executeAndVerify(root, operation,
                PathAddress.pathAddress(PathElement.pathElement(HOST, "localhost"), PathElement.pathElement(SERVER, "server-one")));
    }

    @Test
    public void testRolloutPlans() throws Exception {
        final Resource root = createRootResource();
        final ModelNode operation = new ModelNode();
        final ModelNode change = new ModelNode();
        PathAddress pa = PathAddress.pathAddress(PathElement.pathElement(MANAGEMENT_CLIENT_CONTENT, ROLLOUT_PLANS));
        change.get("domain-resource-address").set(pa.toModelNode());
        change.get("domain-resource-model").set(new ModelNode());
        operation.get(DOMAIN_MODEL).add(change);

        executeAndVerify(root, operation);

        Resource r = root.navigate(pa);
        assertTrue(r instanceof ManagedDMRContentTypeResource);
    }




    private void executeAndVerify(Resource root, ModelNode operation,
                                  PathAddress... expected) throws OperationFailedException {
        executeAndVerify(root, operation, false, expected);
    }

    private void executeAndVerify(Resource root, ModelNode operation, boolean booting,
                                  PathAddress... expected) throws OperationFailedException {
        MockOperationContext operationContext = getOperationContext(root, booting);
        operationContext.expectStep(PathAddress.EMPTY_ADDRESS);
        handler.execute(operationContext, operation);

        Map<OperationContext.Stage, List<OperationAndHandler>> addedSteps = operationContext.verify();

        assertTrue(addedSteps.containsKey(OperationContext.Stage.MODEL));
        List<OperationAndHandler> modelSteps = addedSteps.get(OperationContext.Stage.MODEL);
        assertEquals(1, modelSteps.size());
        OperationAndHandler oah = modelSteps.get(0);

        operationContext = getOperationContext(root, false);
        for (PathAddress address : expected) {
            operationContext.expectStep(address);
        }
        oah.handler.execute(operationContext, oah.operation);
        operationContext.verify();

    }

    private MockOperationContext getOperationContext(Resource root, boolean booting) {
        return new MockOperationContext(root, booting, PathAddress.EMPTY_ADDRESS, false);
    }

}
