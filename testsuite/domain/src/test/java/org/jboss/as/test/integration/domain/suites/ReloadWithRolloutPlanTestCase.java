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

package org.jboss.as.test.integration.domain.suites;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.test.integration.domain.management.util.DomainLifecycleUtil;
import org.jboss.as.test.integration.domain.management.util.DomainTestSupport;
import org.jboss.dmr.ModelNode;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CHILD_TYPE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CONTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.IN_SERIES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT_CLIENT_CONTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_ATTRIBUTE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_CHILDREN_NAMES_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ROLLOUT_PLAN;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ROLLOUT_PLANS;
import static org.jboss.as.test.integration.domain.management.util.DomainTestSupport.validateResponse;
import static org.junit.Assert.assertEquals;

/**
 * Downstream error: if rollout plan is created and master is reloaded, slave is not able to connect to master.
 * (https://issues.jboss.org/browse/WFCORE-3965)
 * Test, which validates this fix, has to be also in upstream, to prevent possible regression.
 *
 * @author <a href="mailto:jondruse@jboss.com">Jiri Ondrusek</a>
 */
public class ReloadWithRolloutPlanTestCase {

    private static final int TIMEOUT = 30000;

    private static DomainTestSupport testSupport;
    private static DomainLifecycleUtil domainMasterLifecycleUtil;
    private static DomainLifecycleUtil domainSlaveLifecycleUtil;

    private static final ModelNode ROLLOUT_PLANS_ADDRESS = new ModelNode().add(MANAGEMENT_CLIENT_CONTENT, ROLLOUT_PLANS);
    private static final ModelNode ROLLOUT_PLAN_A = new ModelNode();

    static {
        ROLLOUT_PLANS_ADDRESS.protect();
        ModelNode main = new ModelNode();
        main.get("main-server-group");
        ROLLOUT_PLAN_A.get(ROLLOUT_PLAN, IN_SERIES).add().get("server-group").set(main);
        ROLLOUT_PLAN_A.protect();
    }

    @BeforeClass
    public static void setupDomain() throws Exception {
        testSupport = DomainTestSuite.createSupport(ReloadWithRolloutPlanTestCase.class.getSimpleName());
        domainMasterLifecycleUtil = testSupport.getDomainMasterLifecycleUtil();
        domainSlaveLifecycleUtil = testSupport.getDomainSlaveLifecycleUtil();
    }

    @AfterClass
    public static void tearDownDomain() throws Exception {
        testSupport = null;
        domainMasterLifecycleUtil = null;
        DomainTestSuite.stopSupport();
    }

    private DomainClient masterClient;

    @Before
    public void setup() throws Exception {
        masterClient = domainMasterLifecycleUtil.getDomainClient();
    }

    @Test
    public void testReloadMasterWithRolloutPlan() throws Exception {
        final ModelNode addressA = ROLLOUT_PLANS_ADDRESS.clone().add(ROLLOUT_PLAN, "testPlan");

        // Add content
        ModelNode op = Util.getEmptyOperation(ADD, addressA);
        op.get(CONTENT).set(ROLLOUT_PLAN_A);
        ModelNode response = masterClient.execute(op);
        validateResponse(response);
        response = masterClient.execute(getReadAttributeOperation(addressA, CONTENT));
        ModelNode returnVal = validateResponse(response);
        assertEquals(ROLLOUT_PLAN_A, returnVal);

        //reload
        reloadMaster();

        // Wait for the slave to reconnect
        waitForHost(masterClient, "slave");

        //remove rollout plan
        op = Util.getEmptyOperation(REMOVE, addressA);
        response = masterClient.execute(op);
        validateResponse(response);
    }


    private void reloadMaster() throws Exception {
        ModelNode op = new ModelNode();
        op.get(OP_ADDR).add(HOST, "master");
        op.get(OP).set("reload");
        op.get("admin-only").set(false);
        domainMasterLifecycleUtil.executeAwaitConnectionClosed(op);

        // Try to reconnect to the hc
        domainMasterLifecycleUtil.connect();
    }

    private static void waitForHost(final ModelControllerClient client, final String hostName) throws Exception {
        final ModelNode operation = new ModelNode();
        operation.get(OP).set(READ_CHILDREN_NAMES_OPERATION);
        operation.get(OP_ADDR).setEmptyList();
        operation.get(CHILD_TYPE).set(HOST);
        final ModelNode host = new ModelNode().set(hostName);
        final long timeout = 30L;
        final TimeUnit timeUnit = TimeUnit.SECONDS;
        final long deadline = System.currentTimeMillis() + timeUnit.toMillis(timeout);
        for(;;) {
            final long remaining = deadline - System.currentTimeMillis();
            final ModelNode result = client.execute(operation);
            if(result.has(OUTCOME) && "success".equals(result.get(OUTCOME).asString()) && result.get(RESULT).asList().contains(host)) {
                return;
            }
            if(remaining <= 0) {
                Assert.fail("Host '"+hostName+"' is not running (in 30 seconds)");
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Assert.fail("Interrupted while waiting for registration of host " + hostName);
            }
        }
    }

    private static ModelNode getReadAttributeOperation(ModelNode address, String attribute) {
        ModelNode op = new ModelNode();
        op.get(OP).set(READ_ATTRIBUTE_OPERATION);
        op.get(OP_ADDR).set(address);
        op.get(NAME).set(attribute);
        return op;
    }

}
