// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.autoscale;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.Zone;
import com.yahoo.config.provisioning.FlavorsConfig;
import com.yahoo.test.ManualClock;
import com.yahoo.transaction.Mutex;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.vespa.hosted.provision.node.IP;
import com.yahoo.vespa.hosted.provision.provisioning.FatalProvisioningException;
import com.yahoo.vespa.hosted.provision.provisioning.HostProvisioner;
import com.yahoo.vespa.hosted.provision.provisioning.HostResourcesCalculator;
import com.yahoo.vespa.hosted.provision.provisioning.ProvisionedHost;
import com.yahoo.vespa.hosted.provision.provisioning.ProvisioningTester;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

class AutoscalingTester {

    private final ProvisioningTester provisioningTester;
    private final Autoscaler autoscaler;
    private final NodeMetricsDb db;
    private final MockHostResourcesCalculator hostResourcesCalculator;

    /** Creates an autoscaling tester with a single host type ready */
    public AutoscalingTester(NodeResources hostResources) {
        this(new Zone(Environment.prod, RegionName.from("us-east")), null, null, asConfig(hostResources));
        provisioningTester.makeReadyNodes(20, "hostFlavor", NodeType.host, 8); // "hostFlavor" generated by asConfig
        provisioningTester.deployZoneApp();
    }

    public AutoscalingTester(Zone zone, List<Flavor> flavors) {
        this(zone,
             new MockHostProvisioner(flavors),
             new InMemoryFlagSource().withBooleanFlag(Flags.ENABLE_DYNAMIC_PROVISIONING.id(), true),
             asConfig(flavors));
    }

    private AutoscalingTester(Zone zone, MockHostProvisioner hostProvisioner, FlagSource flagSource, FlavorsConfig flavorsConfig) {
        provisioningTester = new ProvisioningTester.Builder().zone(zone)
                                                             .flavorsConfig(flavorsConfig)
                                                             .hostProvisioner(hostProvisioner)
                                                             .flagSource(flagSource)
                                                             .build();

        hostResourcesCalculator = new MockHostResourcesCalculator(zone);
        db = new NodeMetricsDb();
        autoscaler = new Autoscaler(hostResourcesCalculator, db, nodeRepository());
    }

    public ApplicationId applicationId(String applicationName) {
        return ApplicationId.from("tenant1", applicationName, "instance1");
    }

    public ClusterSpec clusterSpec(ClusterSpec.Type type, String clusterId) {
        return ClusterSpec.request(type,
                                   ClusterSpec.Id.from(clusterId),
                                   Version.fromString("7"),
                                   false);
    }

    public void deploy(ApplicationId application, ClusterSpec cluster, ClusterResources resources) {
        deploy(application, cluster, resources.nodes(), resources.groups(), resources.nodeResources());
    }

    public void deploy(ApplicationId application, ClusterSpec cluster, int nodes, int groups, NodeResources resources) {
        List<HostSpec> hosts = provisioningTester.prepare(application, cluster, Capacity.fromCount(nodes, resources), groups);
        for (HostSpec host : hosts)
            makeReady(host.hostname());
        provisioningTester.deployZoneApp();
        provisioningTester.activate(application, hosts);
    }

    public void makeReady(String hostname) {
        Node node = nodeRepository().getNode(hostname).get();
        nodeRepository().write(node.with(new IP.Config(Set.of("::" + 0 + ":0"), Set.of())), nodeRepository().lock(node));
        Node host = nodeRepository().getNode(node.parentHostname().get()).get();
        host = host.with(new IP.Config(Set.of("::" + 0 + ":0"), Set.of("::" + 0 + ":2")));
        if (host.state() == Node.State.provisioned)
            nodeRepository().setReady(List.of(host), Agent.system, getClass().getSimpleName());
    }

    public void deactivateRetired(ApplicationId application, ClusterSpec cluster, ClusterResources resources) {
        try (Mutex lock = nodeRepository().lock(application)){
            for (Node node : nodeRepository().getNodes(application, Node.State.active)) {
                if (node.allocation().get().membership().retired())
                    nodeRepository().write(node.with(node.allocation().get().removable()), lock);
            }
        }
        deploy(application, cluster, resources);
    }

    /**
     * Adds measurements with the given resource value and ideal values for the other resources,
     * scaled to take one node redundancy into account.
     * (I.e we adjust to measure a bit lower load than "naively" wanted to offset for the autoscaler
     * wanting to see the ideal load with one node missing.)
     *
     * @param resource the resource we are explicitly setting the value of
     * @param otherResourcesLoad the load factor relative to ideal to use for other resources
     * @param count the number of measurements
     * @param applicationId the application we're adding measurements for all nodes of
     */
    public void addMeasurements(Resource resource, float value, float otherResourcesLoad,
                                int count, ApplicationId applicationId) {
        List<Node> nodes = nodeRepository().getNodes(applicationId, Node.State.active);
        float oneExtraNodeFactor = (float)(nodes.size() - 1.0) / (nodes.size());
        for (int i = 0; i < count; i++) {
            clock().advance(Duration.ofMinutes(1));
            for (Node node : nodes) {
                for (Resource r : Resource.values())
                    db.add(node, r, clock().instant(),
                           (r == resource ? value : (float)r.idealAverageLoad() * otherResourcesLoad) * oneExtraNodeFactor);
            }
        }
    }

    public Optional<ClusterResources> autoscale(ApplicationId application, ClusterSpec cluster) {
        return autoscaler.autoscale(application, cluster, nodeRepository().getNodes(application, Node.State.active));
    }

    public ClusterResources assertResources(String message,
                                            int nodeCount, int groupCount,
                                            double approxCpu, double approxMemory, double approxDisk,
                                            Optional<ClusterResources> actualResources) {
        double delta = 0.0000000001;
        assertTrue(message, actualResources.isPresent());
        assertEquals("Node count " + message, nodeCount, actualResources.get().nodes());
        assertEquals("Group count " + message, groupCount, actualResources.get().groups());
        assertEquals("Cpu: "    + message, approxCpu, Math.round(actualResources.get().nodeResources().vcpu() * 10) / 10.0, delta);
        assertEquals("Memory: " + message, approxMemory, Math.round(actualResources.get().nodeResources().memoryGb() * 10) / 10.0, delta);
        assertEquals("Disk: "   + message, approxDisk, Math.round(actualResources.get().nodeResources().diskGb() * 10) / 10.0, delta);
        return actualResources.get();
    }

    public ManualClock clock() {
        return provisioningTester.clock();
    }

    public NodeRepository nodeRepository() {
        return provisioningTester.nodeRepository();
    }

    private static FlavorsConfig asConfig(NodeResources hostResources) {
        FlavorsConfig.Builder b = new FlavorsConfig.Builder();
        b.flavor(asFlavorConfig("hostFlavor", hostResources));
        return b.build();
    }

    private static FlavorsConfig asConfig(List<Flavor> flavors) {
        FlavorsConfig.Builder b = new FlavorsConfig.Builder();
        for (Flavor flavor : flavors)
            b.flavor(asFlavorConfig(flavor.name(), flavor.resources()));
        return b.build();
    }

    private static FlavorsConfig.Flavor.Builder asFlavorConfig(String flavorName, NodeResources resources) {
        FlavorsConfig.Flavor.Builder flavor = new FlavorsConfig.Flavor.Builder();
        flavor.name(flavorName);
        flavor.minCpuCores(resources.vcpu());
        flavor.minMainMemoryAvailableGb(resources.memoryGb());
        flavor.minDiskAvailableGb(resources.diskGb());
        flavor.bandwidth(resources.bandwidthGbps() * 1000);
        return flavor;
    }

    private static class MockHostResourcesCalculator implements HostResourcesCalculator {

        private final Zone zone;

        public MockHostResourcesCalculator(Zone zone) {
            this.zone = zone;
        }

        @Override
        public NodeResources availableCapacityOf(String flavorName, NodeResources hostResources) {
            if (zone.cloud().value().equals("aws"))
                return hostResources.withMemoryGb(hostResources.memoryGb() + 3);
            else
                return hostResources;
        }

    }

    private static class MockHostProvisioner implements HostProvisioner {

        private final List<Flavor> hostFlavors;

        public MockHostProvisioner(List<Flavor> hostFlavors) {
            this.hostFlavors = hostFlavors;
        }

        @Override
        public List<ProvisionedHost> provisionHosts(List<Integer> provisionIndexes, NodeResources resources, ApplicationId applicationId) {
            Flavor hostFlavor = hostFlavors.stream().filter(f -> f.resources().justNumbers().equals(resources.justNumbers())).findAny()
                                           .orElseThrow(() -> new RuntimeException("No flavor matching " + resources + ". Flavors: " + hostFlavors));

            List<ProvisionedHost> hosts = new ArrayList<>();
            for (int index : provisionIndexes) {
                hosts.add(new ProvisionedHost("host" + index,
                                              "hostname" + index,
                                              hostFlavor,
                                              "nodename" + index,
                                              resources));
            }
            return hosts;
        }

        @Override
        public List<Node> provision(Node host, Set<Node> children) throws FatalProvisioningException {
            throw new RuntimeException("Not implemented");
        }

        @Override
        public void deprovision(Node host) {
            throw new RuntimeException("Not implemented");
        }

    }

}
